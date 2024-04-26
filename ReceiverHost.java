import java.net.*;
import java.nio.ByteBuffer;

public class ReceiverHost {
    private static final int SYN = 4;
    private static final int FIN = 2;
    private static final int ACK = 1;

    private int port;
    private int mtu;
    private int sws;
    private int next_seq_num; // this is the next sequence number that we are going to send
    private int curr_seq_num; // this is the current sequence number we are on
    private int prev_ack_num; // this is the last received acc
    private int next_ack_num; // this is the next byte we expect from the sender
    private int next_base_ack;
    private long timeout_val;
    private int payload_size;
    private boolean finished_receiving;
    private boolean connected;

    private DatagramSocket receive_socket;


    public ReceiverHost(int port, int mtu, int sws, String filePathName){
        this.port = port;
        this.mtu = mtu;
        this.sws = sws;

        curr_seq_num = 0;
        next_seq_num = 0;
        prev_ack_num = -1;
        next_ack_num = 0;
        next_base_ack = 0;
        payload_size = mtu - 24;

        finished_receiving = false;
        connected = false;

        timeout_val = 5L * 1_000_000_000L;

        runReceiver(port, mtu, sws, filePathName);
    }

    private void runReceiver(int port, int mtu, int sws, String filePathName){
        try{
            receive_socket = new DatagramSocket(port);

            byte[] incomingData = new byte[mtu];
            DatagramPacket incomingPacket = new DatagramPacket(incomingData, incomingData.length);

            while(!finished_receiving){
                receive_socket.receive(incomingPacket);

                int dstPort = incomingPacket.getPort();
                InetAddress dstAddr = incomingPacket.getAddress();

                if(verifyAck(incomingData) != -1){
                    printPacket(incomingData, true);
                    receiveUpdate(incomingData);

                    int length = pullLength(incomingData);
                    int actualLength = length >> 3;

                    if(isData(incomingData)){
                        // packet has data
                        // exclude flags from length check
                        if(actualLength > 0){
                            next_ack_num = pullSeqNum(incomingData) + actualLength;

                            if(connected){
                                if(next_ack_num + payload_size < next_base_ack + sws && length == payload_size){
                                    continue;
                                }
                                else{
                                    byte[] ackPacket = buildPacket(new byte[0], 2, curr_seq_num);
                                    printPacket(ackPacket, false);
                                    receive_socket.send(new DatagramPacket(ackPacket, ackPacket.length, dstAddr, dstPort));
                                    sendUpdate(ackPacket);
                                }
                            }
                        }
                        // packet doesn't have data
                        else{
                            // ack is set regardless
                            int flagsTemp = 1;
                            // syn flag is set
                            if(isFlagSet(length, SYN)){
                                flagsTemp += 4;
                            }
                            // fin flag is set
                            if(isFlagSet(length, FIN)){
                                flagsTemp += 2;
                            }

                            next_ack_num = pullSeqNum(incomingData) + 1;

                            byte[] ackPacket = buildPacket(new byte[0], flagsTemp, next_seq_num);
                            printPacket(ackPacket, false);
                            receive_socket.send(new DatagramPacket(ackPacket, ackPacket.length, dstAddr, dstPort));

                            if(prev_ack_num + 1 == next_seq_num){
                                // setTimer(true);
                            }
                            sendUpdate(ackPacket);
                        }


                    }
                    else{
                        if(next_seq_num == 1){
                            connected = true;
                        }else if(isFlagSet(length, FIN)){
                            // close connection
                        }
                    }

                }
                // send duplicate ack, packets not in order
                else{
                    byte[] ackPacket = buildPacket(new byte[0], 2, curr_seq_num);
                    printPacket(ackPacket, false);
                    receive_socket.send(new DatagramPacket(ackPacket, ackPacket.length, dstAddr, dstPort));
                    sendUpdate(ackPacket);
                }
            }
        }catch(Exception e){
            System.exit(1);
        }
    }

    private boolean isData(byte[] packet) {
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

        if(isData(packet)){
            int seqNum = pullSeqNum(packet);
            if(seqNum != next_ack_num){
                return -1;
            }
        }

        return ackNum;
    }

    private void receiveUpdate(byte[] packet){
        int length = pullLength(packet);

        if(isData(packet)) {
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
            // setTimer(false);
        }
    }

    private void sendUpdate(byte[] packet){
        int length = pullLength(packet);

        if(isData(packet)){
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

    private void printPacket(byte[] packet, boolean receive){
        StringBuilder sb = new StringBuilder();

        int length = pullLength(packet);

        if(receive){
            sb.append("rcv");
        }else{
            sb.append("snd");
        }

        sb.append(" ").append(System.nanoTime());

        // check if s-flag is set
        if(((length >> 2) & 1) == 1){
            sb.append("S").append(" ");
        }else{
            sb.append("-").append(" ");
        }

        // check if a-flag is set
        if(((length >> 1) & 1) == 1){
            sb.append("A").append(" ");
        }else{
            sb.append("-").append(" ");
        }

        // check if f-flag is set
        if((length & 1) == 1){
            sb.append("F").append(" ");
        }else{
            sb.append("-").append(" ");
        }

        // data flag is set if length > 0 cause the spec said so ig
        if(length > 0){
            sb.append("D").append(" ");
        }else{
            sb.append("-").append(" ");
        }

        sb.append(pullSeqNum(packet)).append(" ");
        sb.append(pullLength(packet)).append(" ");
        sb.append(pullAck(packet));

        System.out.println(sb);
    }

    private byte[] buildPacket(byte[] data, int flags, int sequenceNumber) {

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
        long currTimeStamp = System.nanoTime();
        buffer3.putLong(currTimeStamp);

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
