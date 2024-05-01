import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.HashMap;

public class ReceiverHost {
    private static final int SYN = 4;
    private static final int FIN = 2;
    private static final int ACK = 1;

    private int next_seq_num; // this is the next sequence number that we are going to send
    private int curr_seq_num; // this is the current sequence number we are on
    private int prev_ack_num; // this is the last received ack
    private int next_ack_num; // this is the next byte we expect from the sender
    private int next_base_ack;

    private final long startTime;
    private final int payload_size;
    private boolean finished_receiving;
    private boolean connected;
    private boolean endOfConnection;

    private boolean temp;

    private DatagramSocket receive_socket;

    private HashMap<Integer, byte[]> recBuffer;

    private int dataReceived;
    private int oooPacketCount;


    public ReceiverHost(int port, int mtu, int sws, String fileName){

        curr_seq_num = 0;
        next_seq_num = 0;
        prev_ack_num = -1;
        next_ack_num = 0;
        next_base_ack = 0;
        payload_size = mtu - 24;

        finished_receiving = false;
        connected = false;
        endOfConnection = false;
        temp = true;

        startTime = System.nanoTime();

        recBuffer = new HashMap<>();

        while(temp){
            runReceiver(port, mtu, sws, fileName);
            resetConnection();
        }
    }

    private void runReceiver(int port, int mtu, int sws, String fileName){
        try {
            receive_socket = new DatagramSocket(port);
        }catch(Exception e){
            System.out.println("Failed to create socket");
            System.exit(-1);
        }

        byte[] incomingData = new byte[mtu];
        DatagramPacket incomingPacket = new DatagramPacket(incomingData, incomingData.length);

        FileOutputStream fileWriter = null;
        try {
            fileWriter = new FileOutputStream(fileName);
        } catch (FileNotFoundException e) {
            System.out.println("The file was not found");
            System.exit(-1);
        }

        assert fileWriter != null;

        while(!finished_receiving){
            try {
                receive_socket.receive(incomingPacket);
            }catch(Exception e){
                System.out.println("Could not receive incoming packet :(");
            }

            int dstPort = incomingPacket.getPort();
            InetAddress dstAddr = incomingPacket.getAddress();

            int length = pullLength(incomingData);
            int actualLength = length >> 3;
            long sentTime = pullTime(incomingData);

            if(verifyAck(incomingData) != -1){
                printPacket(incomingData, true);
                receiveUpdate(incomingData);

                if(isSynFinData(incomingData)){
                    // packet has data
                    // exclude flags from length check
                    if(actualLength > 0){
                        next_ack_num = pullSeqNum(incomingData) + actualLength;

                        if(connected){
                            if(next_ack_num + payload_size < next_base_ack + sws && length == payload_size){
                                continue;
                            }
                            else{
                                sendPacket(dstPort, dstAddr, sentTime, ACK, curr_seq_num);

                                try {
                                    fileWriter.write(pullData(incomingData));
                                } catch (Exception e) {
                                    System.out.println("Failed writing to file");
                                    System.exit(-1);
                                }
                            }
                        }
                    }
                    // packet doesn't have data, SYN/FIN received
                    else{
                        next_ack_num = pullSeqNum(incomingData) + 1;

                        if(isFlagSet(length, SYN)){
                            // respond to SYN from sender
                            sendPacket(dstPort, dstAddr, sentTime, ACK + SYN, next_seq_num);
                        }else{
                            // respond to FIN from sender
                            sendPacket(dstPort, dstAddr, sentTime, ACK + FIN, next_seq_num);
                            endOfConnection = true;
                        }
                    }
                }
                // received an ACK
                else{
                    if(next_seq_num == 1){
                        connected = true;
                    }

                    if(endOfConnection){
                        // close connection, final ack received
                        if(next_seq_num == pullAck(incomingData)){
                            finished_receiving = true;
                        }
                    }
                }
            }
            // send duplicate ack, packets not in order
            else{
                // need to store the received packet in the receiver buffer
                // and ack the last successful byte received
                if(recBuffer.size() + 1 <= sws){
                    recBuffer.put(curr_seq_num, incomingData);
                }

                // not sure if the sequence num is correct here
                sendPacket(dstPort, dstAddr, sentTime, ACK, prev_ack_num);
            }
        }


    }

    private void resetConnection(){
        curr_seq_num = 0;
        next_seq_num = 0;
        prev_ack_num = -1;
        next_ack_num = 0;
        next_base_ack = 0;
    }

    private void sendPacket(int dstPort, InetAddress dstAddr, long sentTime, int flags, int seqNum) {
        byte[] packet = buildPacket(new byte[0], flags, seqNum, sentTime);
        printPacket(packet, !isFlagSet(flags, ACK));

        try {
            receive_socket.send(new DatagramPacket(packet, packet.length, dstAddr, dstPort));
        }catch(Exception e){
            System.out.println("Failed to send ack packet");
            System.exit(-1);
        }
        sendUpdate(packet);
    }

    private boolean isSynFinData(byte[] packet) {
        // check to see if there is data in the packet
        int length = pullLength(packet);
        int actualLength = length >> 3;

        if (actualLength > 0) { // Data exists in the packet
            return true;
        } else {
            if (isFlagSet(length, SYN)) { // check SYN bit
                return true;
            } else { // check FIN bit, returns false if no data is present and SYN/FIN are not set
                return isFlagSet(length, FIN);
            }
        }
    }

    private boolean isFlagSet(int length, int flag){
        // flag var values: SYN = 4, FIN = 2, ACK = 1

        if(flag == SYN){
            return (length >> 2 & 1) == 1;
        }else if(flag == FIN){
            return (length >> 1 & 1) == 1;
        }else if(flag == ACK){
            return (length & 1) == 1;
        }

        return false;
    }

    private int verifyAck(byte[] packet){
        int ackNum = pullAck(packet);
        if(ackNum != next_seq_num){
            return -1;
        }

        if(isSynFinData(packet)){
            int seqNum = pullSeqNum(packet);
            if(seqNum != next_ack_num){
                return -1;
            }
        }

        return ackNum;
    }

    private void receiveUpdate(byte[] packet){
        int length = pullLength(packet);

        if(isSynFinData(packet)) {
            // exclude flags from length check
            if((length >> 3) > 0){
                next_ack_num = pullSeqNum(packet) + (length >> 3);
            }
            else{
                next_ack_num = pullSeqNum(packet) + 1;
            }
        }

        if(isFlagSet(length, ACK)){
            prev_ack_num = pullAck(packet) - 1;
        }
    }

    private void sendUpdate(byte[] packet){
        int length = pullLength(packet);

        if(isSynFinData(packet)){
            curr_seq_num = next_seq_num;
            // exclude flags from length check
            if((length >> 3) > 0){
                next_seq_num = curr_seq_num + (length >> 3);
            }
            else{
                next_seq_num = curr_seq_num + 1;
            }

            next_base_ack = next_ack_num;
        }
    }

    private int pullAck(byte[] packet) {
        ByteBuffer buffer = ByteBuffer.wrap(packet);
        buffer.position(4); // move buffer ahead

        return buffer.getInt();

    }

    private long pullTime(byte[] packet) {
        ByteBuffer buffer = ByteBuffer.wrap(packet);
        buffer.position(8);
        return buffer.getLong();
    }

    private int pullLength(byte[] packet) {
        ByteBuffer buffer = ByteBuffer.wrap(packet);
        buffer.position(16);

        return buffer.getInt();
    }

    private int pullSeqNum(byte[] packet) {

        ByteBuffer buffer = ByteBuffer.wrap(packet);
        buffer.position(0); // move buffer ahead

        return buffer.getInt();

    }

    private byte[] pullData(byte[] packet) {

        ByteBuffer buffer = ByteBuffer.wrap(packet);
        buffer.position(24); // move buffer ahead

        byte[] temp = new byte[buffer.remaining()];
        buffer.get(temp);
        return temp;
    }

    private void printPacket(byte[] packet, boolean receive){
        StringBuilder sb = new StringBuilder();
        if (receive) {
            sb.append("rcv");
        } else {
            sb.append("snd");
        }

        long elapsedTimeNano = System.nanoTime() - startTime;

        double elapsedTimeSeconds = (double) elapsedTimeNano / 1_000_000_000.0;

        sb.append(" ");
        sb.append(String.format("%.2f", elapsedTimeSeconds));
        sb.append(" ");

        int length = pullLength(packet);
        int actualLength = length >> 3;
        if (((length >> 2) & 1) == 1) { // SYN
            sb.append("S");
            sb.append(" ");

        } else {
            sb.append("-");
            sb.append(" ");
        }

        if ((length & 1) == 1) { // ACK bit
            sb.append("A");
            sb.append(" ");

        } else {
            sb.append("-");
            sb.append(" ");

        }

        if (((length >> 1) & 1) == 1) { // FIN
            sb.append("F");
            sb.append(" ");

        } else {
            sb.append("-");
            sb.append(" ");
        }

        if (actualLength > 0) {
            sb.append("D");
        } else {
            sb.append("-");
        }

        int seqNum = pullSeqNum(packet);
        sb.append(" ").append(seqNum);

        sb.append(" ").append(actualLength);

        int ackNum = pullAck(packet);
        sb.append(" ").append(ackNum);

        System.out.println(sb.toString());
    }

    private byte[] buildPacket(byte[] data, int flags, int sequenceNumber, long timestamp) {

        // the first 4 bytes are the sequence number
        byte[] sequenceNumberBytes = new byte[4];
        ByteBuffer buffer = ByteBuffer.wrap(sequenceNumberBytes);
        buffer.putInt(sequenceNumber);

        // the next 4 bytes are the current ack intially 0
        byte[] currentAckBytes = new byte[4];
        ByteBuffer buffer2 = ByteBuffer.wrap(currentAckBytes);
        buffer2.putInt(this.next_ack_num);

        // now to do the timestamp
        byte[] timeStamp = new byte[8];
        ByteBuffer buffer3 = ByteBuffer.wrap(timeStamp);

        boolean isAck = isFlagSet(flags, ACK);

        if(isAck && timestamp == 0){
            buffer3.putLong(timestamp);
        }else{
            long currTimeStamp = System.nanoTime();
            buffer3.putLong(currTimeStamp);
        }

        // now to do the length field
        int length = data.length; // this should be 0 initially

        // now to make room for the flag bits

        // TODO: I am not sure about this
        length |= flags & 0b111;

        byte[] lengthBytes = new byte[4];
        ByteBuffer buffer4 = ByteBuffer.wrap(lengthBytes);
        buffer4.putInt(length);

        // no the zeros field
        byte[] zeros = new byte[2];

        // TODO: need to fill in the checksum
        short checksum = 0;
        byte[] checkSumBytesZeros = new byte[2];
        ByteBuffer buffer5 = ByteBuffer.wrap(checkSumBytesZeros);
        buffer5.putShort(checksum);

        int totalLength = 4 + 4 + 8 + 4 + 2 + 2 + data.length;

        byte[] packet = new byte[totalLength];

        ByteBuffer packetBuffer = ByteBuffer.wrap(packet);
        packetBuffer.put(sequenceNumberBytes);
        packetBuffer.put(currentAckBytes);
        packetBuffer.put(timeStamp);
        packetBuffer.put(lengthBytes);
        packetBuffer.put(zeros);
        packetBuffer.put(checkSumBytesZeros);
        packetBuffer.put(data);

        short newChecksum = calculateChecksum(packet); // this should be of lenght 2 now we just have to reconstruct the
        // packet

        byte[] checksumBytes = new byte[2];
        checksumBytes[0] = (byte) ((newChecksum >> 8) & 0xFF);
        checksumBytes[1] = (byte) (newChecksum & 0xFF);

        byte[] returnPacket = new byte[totalLength];
        ByteBuffer returnBuffer = ByteBuffer.wrap(returnPacket);
        returnBuffer.put(sequenceNumberBytes);
        returnBuffer.put(currentAckBytes);
        returnBuffer.put(timeStamp);
        returnBuffer.put(lengthBytes);
        returnBuffer.put(zeros);
        returnBuffer.put(checksumBytes);
        returnBuffer.put(data);

        return returnPacket;

    }

    private short calculateChecksum(byte[] packet) {

        // need to zero out the checksum field
        packet[22] = 0x00;
        packet[23] = 0x00;

        int sum = 0;
        int length = packet.length; // lets say lenght is 24
        if (length % 2 != 0) {
            length++;
            byte[] paddedData = new byte[length];
            System.arraycopy(packet, 0, paddedData, 0, packet.length);
            paddedData[length - 1] = 0x00;
            packet = paddedData;
        }

        // Calculate the checksum in 16-bit segments
        for (int i = 0; i < length; i += 2) {
            int segment = ((packet[i] & 0xFF) << 8) | (packet[i + 1] & 0xFF);
            sum += segment;
        }

        // Add carry bits to sum
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }

        return (short) (~sum & 0xFFFF);
    }

}
