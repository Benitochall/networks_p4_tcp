import java.net.*;
import java.nio.ByteBuffer;
import java.io.*;
import java.util.*;

public class SendHost {

    // all the instance vars
    private int port;
    private String destIP;
    private int dst_port;
    private String fileName;
    private int mtu;
    private int prev_ack; // this is the last received acc
    private int next_seq_num; // this is the next sequence number that we are going to send
    private int curr_seq_num; // this is the current sequence number we are on,
    private int next_ack_num; // this is the next byte we expect from the sender
    private boolean foundHost;
    private int slidingWindowSize;
    private boolean instantiateFIN;
    private boolean sentFin;
    private boolean isRetrasnmitting;

    private DatagramSocket server_socket;
    private InetAddress name_dst_ip;
    private boolean lastPacket;
    private boolean hasSentLastPacket = false;

    // a lock to protect the next_seq_num
    private final Object lock = new Object();

    private int dataSegmentSize;
    private int num_incorrect_checksums;
    private int num_packets_sent;
    private int amountDataSent;
    private int num_duplicate_acks;
    private int num_retransmissions;

    private long ERTT;
    private long EDEV;

    private long timeout = 5L * 1_000_000_000L;

    private HashMap<Integer, byte[]> packets; // everytime we send a packet we need to have it
    private HashMap<Integer, Timer> timers;
    private HashMap<Integer, TCPState> stateus;
    private HashMap<Integer, Integer> dupackcountlist;
    private ArrayList<Integer> SequenceNumbers;
    private ArrayList<Integer> DuplicateAcks;
    private long startTime;
    private ArrayList<Integer> retransmitts;

    // this is the host that will send data
    public SendHost(int port, String destIP, int destinationPort, String fileName, int mtu, int sws) {
        // the constructor
        this.port = port;
        this.destIP = destIP;
        this.dst_port = destinationPort;
        this.fileName = fileName;
        this.mtu = mtu;
        this.prev_ack = -1; // the first ack is always 0
        this.next_seq_num = 0;
        this.curr_seq_num = 0;
        this.next_ack_num = 0; // this is what we expect back when receiving data back
        this.foundHost = false;
        this.slidingWindowSize = sws * this.mtu;
        this.dataSegmentSize = mtu - 24;
        this.lastPacket = false;
        packets = new HashMap<>();
        timers = new HashMap<>();
        stateus = new HashMap<>();
        SequenceNumbers = new ArrayList<>();
        DuplicateAcks = new ArrayList<>();
        retransmitts = new ArrayList<>();
        dupackcountlist = new HashMap<>();
        this.ERTT = 0;
        this.EDEV = 0;
        instantiateFIN = false;
        this.sentFin = false;
        this.isRetrasnmitting = false;
        this.num_packets_sent = 0;
        this.amountDataSent = 0;
        this.num_retransmissions = 0;
        this.startTime = System.nanoTime();

        try {
            this.server_socket = new DatagramSocket(this.port);

        }
        // opens up a socket on the port
        catch (SocketException e) {
            System.exit(-1);
        }

        RecThread receiver_thread = new RecThread();
        SendThread sender_thread = new SendThread();

        // start threads
        receiver_thread.start();
        sender_thread.start();

        synchronized (lock) {

            byte[] data = buildPacket(new byte[0], 4, 0);

            try {
                name_dst_ip = InetAddress.getByName(this.destIP);
            } catch (UnknownHostException e) {
                System.out.println("Could not find the host");
                System.exit(-1);
            }

            DatagramPacket packet = new DatagramPacket(data, data.length, name_dst_ip, this.dst_port);

            try {
                server_socket.send(packet);
            }

            catch (Exception e) {
                System.out.println("Sending threw error");
                System.exit(-1);
            }

            // add to hashmap
            packets.put(next_seq_num, data);
            SequenceNumbers.add(next_seq_num);
            // start timer
            startTimer(next_seq_num);
            // add status
            stateus.put(next_seq_num, new TCPState(next_seq_num, curr_seq_num, next_ack_num));
            printPacket(data, false);

            next_seq_num++; // increments by 1
            num_packets_sent++;

        }

        try {
            receiver_thread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        try {
            sender_thread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        try {

            receiver_thread.join();
            sender_thread.join();
        }

        catch (InterruptedException e) {
            System.out.println("Threads interrupted");
            System.exit(-1);
        }

    }

    public int getDataTransfered() {
        return amountDataSent;
    }

    public int getNumPacketsSent() {
        return num_packets_sent;
    }

    public int getIncorrectChecksums() {
        return num_incorrect_checksums;
    }

    public int getRetransmissions() {
        return num_retransmissions;
    }

    public int getDuplicateAcks() {
        return num_duplicate_acks;
    }

    public void incrementDupackCount(int seqnum) {
        if (dupackcountlist.containsKey(seqnum)) {
            int count = dupackcountlist.get(seqnum);
            count++;
            // if (count == 16) {
            //     System.out.println("Too many retransmitts. Exiting program");
            //     System.exit(-1);
            // }
            dupackcountlist.put(seqnum, count);
        } else {
            dupackcountlist.put(seqnum, 1);
        }
       
    }

    public byte[] buildPacket(byte[] data, int flags, int sequenceNumber) {

        // the first 4 bytes are the sequence number
        byte[] sequenceNumberBytes = new byte[4];
        ByteBuffer buffer = ByteBuffer.wrap(sequenceNumberBytes);
        buffer.putInt(sequenceNumber);

        // the next 4 bytes are the current ack initially 0
        byte[] currentAckBytes = new byte[4];
        ByteBuffer buffer2 = ByteBuffer.wrap(currentAckBytes);
        buffer2.putInt(this.next_ack_num);

        // now to do the timestamp
        byte[] timeStamp = new byte[8];
        ByteBuffer buffer3 = ByteBuffer.wrap(timeStamp);
        long currTimeStamp = System.nanoTime();
        buffer3.putLong(currTimeStamp);

        // now to do the length field
        int length = data.length;
        length = length << 3;

        // now to make room for the flag bits

        // TODO: I am not sure about this
        length |= flags & 0b111;

        byte[] lengthBytes = new byte[4];
        ByteBuffer buffer4 = ByteBuffer.wrap(lengthBytes);
        buffer4.putInt(length);

        // now the zeros field
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

        short newChecksum = calculateChecksum(packet); // this should be of length 2 now we just have to reconstruct the
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

    public short calculateChecksum(byte[] packet) {

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

        short checksum = (short) (~sum & 0xFFFF);
        return checksum;
    }

    public void recalculateTimeout(int S, long start_time) {
        long SRTT;
        long SDEV;

        if (S == 0) {
            ERTT = System.nanoTime() - start_time;
            EDEV = 0;
            timeout = 2 * ERTT;
        } else {
            SRTT = System.nanoTime() - start_time;
            SDEV = Math.abs(SRTT - ERTT);
            ERTT = (long) (0.875 * ERTT) + (long) ((1 - 0.875) * SRTT);
            EDEV = (long) (0.75 * EDEV) + (long) ((1 - 0.75) * SDEV);
            timeout = ERTT + 4 * EDEV;
        }

        long timeoutMilliseconds = timeout / 1_000;
        // System.out.println("Timeout in micro: " + timeoutMilliseconds);
    }

    public int pullAck(byte[] packet) {

        ByteBuffer buffer = ByteBuffer.wrap(packet);
        buffer.position(4);

        return buffer.getInt();

    }

    public int pullSeqNum(byte[] packet) {

        ByteBuffer buffer = ByteBuffer.wrap(packet);
        buffer.position(0);

        return buffer.getInt();

    }

    public int pullLength(byte[] packet) {
        ByteBuffer buffer = ByteBuffer.wrap(packet);
        buffer.position(16);
        int length = buffer.getInt();

        return length;

    }

    public short pullChecksum(byte[] packet) {
        ByteBuffer buffer = ByteBuffer.wrap(packet);
        buffer.position(22);
        return buffer.getShort();

    }

    public boolean isData(byte[] packet) {
        // check to see if there is data in the packet
        int length = pullLength(packet);
        int actualLength = length >> 3;

        if (actualLength > 0) {
            return true;
        } else if (((length >> 2) & 1) == 1) { // SYN bit
            return true;

        } else if (((length >> 1) & 1) == 1) { // FIN bit
            return true;
        } else {
            return false;
        }

    }

    public void updateVarsSend(byte[] packet, boolean isData) {
        int length = pullLength(packet);
        int actualLength = length >> 3;
        if (isData) {
            curr_seq_num = next_seq_num; // we just send a packet of 10 bytes, curr becomes 1
            if (actualLength > 0) {
                next_seq_num = curr_seq_num + actualLength;// next becomes 11
            } else {
                next_seq_num = curr_seq_num + 1;
            }
        }

    }

    public long pullTime(byte[] packet) {
        ByteBuffer buffer = ByteBuffer.wrap(packet);
        buffer.position(8);
        return buffer.getLong();
    }

    public void updateVarsRec(byte[] packet, boolean isData) {
        // System.out.println("updating vars recived");
        // so if the data message that comes in has data
        int packet_seq_num = pullSeqNum(packet); // gets the sequence number out of the packet // 0
        int length = pullLength(packet);
        int actualLength = length >> 3;

        if (isData) {
            // this is where we need to set the nextAck to be the sequence number + the data
            // length
            if (actualLength > 0) {
                // lets say we send 1 byte of data with a seq of 11 then the next ack should be
                // 12
                next_ack_num = packet_seq_num + actualLength;
            } else {
                // in this case we didnt get any data
                next_ack_num = packet_seq_num + 1;

            }

        }
        // now handle case where we didn't get data back
        if (((length >> 2) & 1) == 1) { // SYN bit
            // we have recived a SYN from the host and now are sucessfully set up
            this.foundHost = true;

        }
        if (((length >> 1) & 1) == 1) { // FIN bit
            // now we have ended the connection after reciveing a fin back
            this.foundHost = false;
            instantiateFIN = true;

        }
        if ((length & 1) == 1) {
            prev_ack = pullAck(packet) - 1;

        }

    }

    public boolean isSYNFIN(byte[] packet) {
        // so if the data message that comes in has data
        int length = pullLength(packet);

        // now handle case where we didn't get data back
        if (((length >> 2) & 1) == 1) { // SYN bit
            // we have recived a SYN from the host and now are sucessfully set up
            return true;

        } else if (((length >> 1) & 1) == 1) { // FINBIt
            return true;
        }
        return false;

    }

    public boolean isFIN(byte[] packet) {
        // so if the data message that comes in has data
        int length = pullLength(packet);

        if (((length >> 1) & 1) == 1) { // FINBIt
            return true;
        }
        return false;

    }

    private boolean allElementsEqual(ArrayList<Integer> list) {
        int first = list.get(0);
        for (int i = 1; i < list.size(); i++) {
            if (list.get(i) != first) {
                return false;
            }
        }
        return true;
    }

    public boolean validateChecksum(byte[] data) {

        // first check the acutal length of the data
        int length = pullLength(data);
        int actualLength = length >> 3;
        actualLength = actualLength + 24;

        // create a new packet to validate checksum
        byte[] usefulData = new byte[actualLength];

        System.arraycopy(data, 0, usefulData, 0, actualLength); // copies the unbounded lenth of data into

        // now we have to pull out the checksum
        short checksum = pullChecksum(usefulData);

        // now that we got the checksum need to calculate it of the packet
        short calculatedChecksum = calculateChecksum(usefulData);

        return checksum == calculatedChecksum;
    }

    private void resetState(int seqNum) {

        TCPState old_state = stateus.get(seqNum);
        next_seq_num = old_state.getTCPState_next_seq_num(); // 977
        curr_seq_num = old_state.getTCPState_curr_seq_num(); // 1
        next_ack_num = old_state.getTCPState_next_ack_num(); // 1

        // get the current next packet
        // need to get the packet

        next_seq_num = next_seq_num - (mtu-24); 
       

        synchronized (lock) {

            while (isRetrasnmitting) {
                if (next_seq_num + dataSegmentSize < prev_ack + slidingWindowSize + 1) {

                    num_retransmissions++;
                    incrementDupackCount(next_seq_num);

                    byte[] data = packets.get(next_seq_num); // this is 977
                    // now cancel the timer on this packet
                    int length = pullLength(data);
                    int actualLength = length >> 3;

                    cancelTimer(next_seq_num + actualLength); // cancels the timer
                    retransmitts.add(next_seq_num + actualLength);

                    data = updateRetransmit(data);

                    DatagramPacket packet = new DatagramPacket(data, data.length,
                            name_dst_ip,
                            dst_port);
                    try {
                        server_socket.send(packet);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    num_packets_sent++;

                    printPacket(data, false); // send last data

                    int last_seq_num = SequenceNumbers.get(SequenceNumbers.size() - 1);

                    if (last_seq_num == next_seq_num) {
                        isRetrasnmitting = false;
                    }

                    updateVarsSend(data, isData(data));
                }
                startTimer(next_seq_num);
            }
        }

    }

    public void printPacket(byte[] packet, boolean receive) {
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
        sb.append(" " + seqNum);

        sb.append(" " + actualLength);

        int ackNum = pullAck(packet);
        sb.append(" " + ackNum);

        System.out.println(sb.toString());
    }

    private void startTimer(int seqNum) {
        // System.out.println("Start timer for" + seqNum + "\n");
        long timeoutTime = System.nanoTime() + timeout;
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                isRetrasnmitting = true;
                cancelTimer(seqNum); // remove the sequence number
                resetState(seqNum);

            }
        }, timeoutTime);
        timers.put(seqNum, timer);
    }

    private void cancelTimer(int seqNum) { // TODO: cancel timer everytime there is an ack
        Timer timer = timers.get(seqNum);
        if (timer != null) {
            // System.out.println("Canceled timer for" + seqNum);
            timer.cancel();
            timers.remove(seqNum);
        }
    }

    public byte[] updateRetransmit(byte[] packet) {

        // step1 reset the time
        long currTimeStamp = System.nanoTime();

        byte[] valueBytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            valueBytes[i] = (byte) (currTimeStamp >> (i * 8));
        }

        // Replace bytes 8 through 15 in the byte array
        System.arraycopy(valueBytes, 0, packet, 8, 8);

        // next I need to recalculate the checksum
        short checksum = calculateChecksum(packet);

        // Convert the checksum to bytes
        byte[] checksumBytes = new byte[2];
        checksumBytes[0] = (byte) (checksum >> 8);
        checksumBytes[1] = (byte) checksum;

        System.arraycopy(checksumBytes, 0, packet, 22, 2);

        // now should be all updated

        return packet;

    }

    public class SendThread extends Thread {

        @Override
        public void run() {
            // here we need to run the sending data thread
            // need to constantly send data
            try {
                FileInputStream fileReader = new FileInputStream(new File(fileName));

                // we con only send a message of the size of the window
                while (!instantiateFIN) {

                    byte[] sendDataBytes = new byte[slidingWindowSize];
                    name_dst_ip = InetAddress.getByName(destIP);

                    // System.out.println(foundHost);
                    // System.out.println(lastPacket);

                    if (foundHost && !lastPacket) { // we have received a message from the host

                        if ((next_seq_num + dataSegmentSize < prev_ack + slidingWindowSize + 1) && !isRetrasnmitting) {

                            synchronized (lock) {

                                // // need to retransmit all the packets
                                // // get the current next packet
                                // // need to get the packet
                                // System.out.println("is Retransmitting");
                                // System.out.println(next_seq_num);

                                // byte[] data = packets.get(next_seq_num); // this is 977
                                // // now cancel the timer on this packet
                                // int length = pullLength(data);
                                // int actualLength = length >> 3;

                                // cancelTimer(next_seq_num + actualLength); // cancels the timer

                                // data = updateRetransmit(data);

                                // DatagramPacket packet = new DatagramPacket(data, data.length,
                                // name_dst_ip,
                                // dst_port);
                                // server_socket.send(packet);
                                // num_packets_sent++;

                                // printPacket(sendDataBytes, false); // send last data

                                // // TODO: figure out when to stop
                                // int last_seq_num = SequenceNumbers.get(SequenceNumbers.size() - 1);

                                // if (last_seq_num == next_seq_num) {
                                // isRetrasnmitting = false;
                                // }

                                // updateVarsSend(sendDataBytes, isData(sendDataBytes));
                                // startTimer(next_seq_num);

                                if (next_seq_num == 1 && !lastPacket) {
                                    byte[] data = new byte[dataSegmentSize]; // this is 500
                                    int fileDataLength = fileReader.read(data, 0, dataSegmentSize);
                                    amountDataSent += fileDataLength;
                                    if (fileDataLength < dataSegmentSize) {
                                        lastPacket = true;
                                    }

                                    byte[] dataPacket = new byte[fileDataLength];
                                    // System.out.println(dataPacket.length);

                                    System.arraycopy(data, 0, dataPacket, 0, fileDataLength);

                                    // now the dataPacket should contain only the file data
                                    sendDataBytes = buildPacket(dataPacket, 1, next_seq_num);

                                } else {
                                    // we send more data but not the first byte
                                    byte[] dataToSend = new byte[dataSegmentSize];
                                    // in this case we can fill the entire segment with data

                                    int fileDataLength = fileReader.read(dataToSend, 0, dataSegmentSize);

                                    // check if this is the last data
                                    if (fileDataLength == -1) {
                                        sendDataBytes = buildPacket(new byte[0], 0, next_seq_num);
                                        lastPacket = true;
                                    } else {
                                        // this is the case where we have valid data
                                        byte[] data = new byte[fileDataLength];
                                        amountDataSent += data.length;
                                        System.arraycopy(dataToSend, 0, data, 0, fileDataLength);

                                        // check
                                        if (dataSegmentSize > data.length) {
                                            lastPacket = true;
                                        }
                                        sendDataBytes = buildPacket(data, 0, next_seq_num);
                                        // TODO: do we need to send an ack with the the data? I dont think so

                                    }

                                }
                                // now send the data
                                DatagramPacket packet = new DatagramPacket(sendDataBytes, sendDataBytes.length,
                                        name_dst_ip,
                                        dst_port);

                                server_socket.send(packet);
                                num_packets_sent++;
                                packets.put(next_seq_num, sendDataBytes);
                                SequenceNumbers.add(next_seq_num);

                                printPacket(sendDataBytes, false);
                                updateVarsSend(sendDataBytes, isData(sendDataBytes)); // now send the data

                                stateus.put(next_seq_num,
                                        new TCPState(next_seq_num, curr_seq_num, next_ack_num));
                                startTimer(next_seq_num);

                            }

                        }

                    }
                }
            } catch (FileNotFoundException f) {
                System.out.println("The file was not found");
                System.exit(-1);
            } catch (IOException io) {
                System.out.println("Reading file into data buffer failed");
                System.exit(-1);
            }
        }

    }

    public class RecThread extends Thread {

        @Override
        public void run() {
            byte[] data = new byte[mtu];
            DatagramPacket packet = new DatagramPacket(data, mtu); // data now stores the packet

            while (!instantiateFIN) {
                // System.out.println("In reciveve");

                try {
                    server_socket.receive(packet); // this will wait here

                } catch (Exception e) {
                    System.out.println("Reciving packet threw error");
                }

                if (validateChecksum(data)) {

                    int ackNumber = pullAck(data); // this is 1
                    prev_ack = ackNumber;

                    // System.out.println("seq_num: " + pullSeqNum(data));
                    // int length = pullLength(data);
                    // int actualLength = length >> 3;

                    printPacket(data, true);
                    // every packet that arrives we want to print

                    try {
                        name_dst_ip = InetAddress.getByName(destIP);
                    } catch (UnknownHostException e) {
                        System.out.println("Could not find the host");
                        System.exit(-1);
                    }
                    synchronized (lock) {
                        DuplicateAcks.add(ackNumber);

                        if (DuplicateAcks.size() == 5) {
                            DuplicateAcks.remove(0);
                        }
                        int size = DuplicateAcks.size();

                        if (DuplicateAcks.size() > 1
                                && (DuplicateAcks.get(size - 1).equals(DuplicateAcks.get(size - 2)))) {
                            num_duplicate_acks++;
                        }
                        // System.out.println("DuplicateAcks array contents: " + DuplicateAcks);

                        if (DuplicateAcks.size() == 4 && allElementsEqual(DuplicateAcks)) {
                            isRetrasnmitting = true;
                            resetState(ackNumber);
                            DuplicateAcks.clear(); // Clear the list of duplicate acks
                        }
                        // else we just ignore and continue on

                        else {

                            // recived the ack so we can cancel the timer
                            cancelTimer(curr_seq_num); // TODO: need to figure out if it is a duplicate ack
                            updateVarsRec(data, isData(data));

                            byte[] returned_ack = packets.get(curr_seq_num); // gets the old packet back

                            if (returned_ack != null && !retransmitts.contains(ackNumber)) {
                                // now we need to pull out the time
                                long start_time = pullTime(returned_ack);
                                recalculateTimeout(curr_seq_num, start_time);
                            }

                            if (isData(data)) { // this evaluates to true when we have a SYN or a fin
                                // System.out.println("here1");

                                if (isSYNFIN(data)) {
                                    curr_seq_num += 1;

                                }
                                if (isFIN(data)) {
                                    instantiateFIN = true;
                                }

                                // now we need to send a packet back that we recived the data
                                // flags = 1 means the ACK flag is set
                                byte[] ackToSend = buildPacket(new byte[0], 1, curr_seq_num);

                                // this will build a packet that contains the new ack number

                                // now we neede to send the datagram
                                DatagramPacket ackDatagram = new DatagramPacket(ackToSend, ackToSend.length,
                                        name_dst_ip,
                                        dst_port);

                                try {
                                    server_socket.send(ackDatagram); // send the packet
                                    num_packets_sent++;

                                } catch (IOException e) {
                                    System.out.println("Failed to send packet");
                                    System.exit(-1);
                                }
                                printPacket(ackToSend, false);

                            } else {

                                if (lastPacket && !hasSentLastPacket) {
                                    hasSentLastPacket = true;
                                    // need to send a FIN packet to the server
                                    byte[] finData = buildPacket(new byte[0], 2, next_seq_num); // TODO: check this
                                    // System.out.println("seq_num: " + finData);
                                    // int l = pullLength(finData);
                                    // int al = l >> 3;

                                    // System.out.println("amount data: " + al);
                                    // System.out.println("ack: " + pullAck(finData));

                                    DatagramPacket finDatagramPacket = new DatagramPacket(finData, finData.length,
                                            name_dst_ip, dst_port);
                                    try {
                                        server_socket.send(finDatagramPacket);
                                        num_packets_sent++;

                                    } catch (Exception e) {
                                        System.out.println("Failed to send FIN packet");
                                        System.exit(-1);
                                    }
                                    packets.put(next_seq_num, finData);
                                    SequenceNumbers.add(next_seq_num);
                                    startTimer(next_seq_num);
                                    printPacket(finData, false);

                                    updateVarsSend(finData, isData(finData));

                                }

                            }
                        }

                    }
                } else {
                    // checksum was not valid
                    num_incorrect_checksums++;
                }
                // System.out.println("Out of recieve");
            }
        }

    }

    // The tcp state class
    public class TCPState {
        private int TCPState_next_seq_num;
        private int TCPState_curr_seq_num;
        private int TCPState_next_ack_num;

        public TCPState(int TCPState_next_seq_num, int TCPState_curr_seq_num,
                int TCPState_next_ack_num) {
            this.TCPState_next_seq_num = TCPState_next_seq_num;
            this.TCPState_curr_seq_num = TCPState_curr_seq_num;
            this.TCPState_next_ack_num = TCPState_next_ack_num;
        }

        public int getTCPState_next_seq_num() {
            return TCPState_next_seq_num;
        }

        public int getTCPState_curr_seq_num() {
            return TCPState_curr_seq_num;
        }

        public int getTCPState_next_ack_num() {
            return TCPState_next_ack_num;
        }
    }

}
