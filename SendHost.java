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

    // a lock to protect the next_seq_num
    private final Object lock = new Object();

    private int dataSegmentSize;
    private int num_incorrect_checksums;
    private int num_packets_sent;
    private int totalRetransmissions;
    private int amountDataSent;
    private int num_duplicate_acks;

    private long ERTT;
    private long EDEV;

    private long timeout = 5L * 1_000_000_000L;

    private HashMap<Integer, byte[]> packets; // everytime we send a packet we need to have it
    private HashMap<Integer, Timer> timers;
    private HashMap<Integer, TCPState> stateus;
    private ArrayList<Integer> SequenceNumbers;
    private ArrayList<Integer> DuplicateAcks;
    private long startTime;

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
        this.slidingWindowSize = sws;
        this.dataSegmentSize = mtu - 24; // this is the maximum amount of data we can send across the network at a time
        // this is because we need space for header
        this.lastPacket = false;
        packets = new HashMap<>();
        timers = new HashMap<>();
        stateus = new HashMap<>();
        SequenceNumbers = new ArrayList<>();
        DuplicateAcks = new ArrayList<>();
        this.ERTT = 0;
        this.EDEV = 0;
        instantiateFIN = false;
        this.sentFin = false;
        this.isRetrasnmitting = false;
        this.totalRetransmissions = 0;
        this.num_packets_sent = 0;
        this.amountDataSent = 0;
        this.startTime = System.nanoTime();

        // first thing that needs to be done is a socket opened
        try {
            this.server_socket = new DatagramSocket(this.port);

        }
        // opens up a socket on the port
        catch (SocketException e) {
            System.exit(-1);
        }

        // ok now that the server is accepting packets on this port we need to start
        // some threads

        RecThread receiver_thread = new RecThread();
        SendThread sender_thread = new SendThread();

        // start threads
        receiver_thread.start();
        sender_thread.start();

        // now we need to send out the first part of the three-way handshake
        // need to build up the packet
        // we need the 29th bit to be a 1 because this will be a SYN

        // there is no data in the first packet

        // in binary, we will represent all three flags as 7
        // just

        synchronized (lock) { // we cannot let other threads execute while this is happening

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
            stateus.put(next_seq_num, new TCPState(prev_ack, next_seq_num, curr_seq_num, next_ack_num));
            printPacket(data, false);

            // now we need to update the next_sequence number because it should be 1
            next_seq_num++; // increments by 1
            num_packets_sent++;

            // ok we just sent a packet with a sequence number of 0 and a current ack of 0
            // the next ack should come back as 1
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
        // now print out summary info
        printSummary();

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
            ERTT = (long) 0.875 * ERTT + (long) (1 - 0.875) * SRTT;
            EDEV = (long) 0.75 * EDEV + (long) (1 - 0.75) * SDEV;
            timeout = ERTT + 4 * EDEV;
        }

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
        prev_ack = old_state.getTCPState_prev_ack(); // TODO: change this
        prev_ack += pullLength(packets.get(seqNum));
        next_seq_num = old_state.getTCPState_next_seq_num();
        curr_seq_num = old_state.getTCPState_curr_seq_num();
        next_ack_num = old_state.getTCPState_next_ack_num();
        isRetrasnmitting = true; // TODO: need to check to see if the sliding window is good

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
        long timeoutTime = System.nanoTime() + timeout;
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                cancelTimer(seqNum); // remove the sequence number
                resetState(seqNum);

            }
        }, timeoutTime);
        timers.put(seqNum, timer);
    }

    private void cancelTimer(int seqNum) { // TODO: cancel timer everytime there is an ack
        Timer timer = timers.get(seqNum);
        if (timer != null) {
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

    public void printSummary() {
        // Amount of Data transferred/received
        System.out.println("Amount of Data transferred/received: " + amountDataSent + " bytes");

        // Number of packets sent/received
        System.out.println("Number of packets sent: " + num_packets_sent);

        // Number of out-of-sequence packets discarded
        System.out.println("Number of out-of-sequence packets discarded: "); // TODO

        // Number of packets discarded due to incorrect checksum
        System.out.println("Number of packets discarded due to incorrect checksum: " + num_incorrect_checksums);

        // Number of retransmissions
        System.out.println("Number of retransmissions: " + totalRetransmissions);

        // Number of duplicate acknowledgements
        System.out.println("Number of duplicate acknowledgements: " + num_duplicate_acks);

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
                    // System.out.println("Not instantiate fin");

                    byte[] sendDataBytes = new byte[slidingWindowSize];
                    name_dst_ip = InetAddress.getByName(destIP);

                    // System.out.println(foundHost);
                    // System.out.println(lastPacket);

                    if (foundHost && !lastPacket) { // we have received a message from the host
                        //System.out.println("Start sending data1");

                        // need to check the size of the sliding window
                        // TODO: need to correctly update prev_ack on retranmit
                        // System.out.println("next_seq_num: " + next_seq_num);
                        // System.out.println("dataSegmentSize: " + dataSegmentSize);
                        // System.out.println("prev_ack: " + prev_ack);
                        // System.out.println("slidingWindowSize: " + slidingWindowSize);
                        
                        if (next_seq_num + dataSegmentSize < prev_ack + slidingWindowSize + 1) {
                            // System.out.println("about to send the first packet"); 

                            synchronized (lock) {
                                if (isRetrasnmitting) {
                                    // need to retransmit all the packets
                                    // get the current next packet
                                    cancelTimer(next_seq_num); // cancel old timer if there is one

                                    byte[] data = packets.get(next_seq_num); // get packet
                                    data = updateRetransmit(data);

                                    DatagramPacket packet = new DatagramPacket(data, data.length,
                                            name_dst_ip,
                                            dst_port);
                                    server_socket.send(packet);
                                    printPacket(sendDataBytes, false); // send last data

                                    startTimer(next_seq_num);
                                    int last_seq_num = SequenceNumbers.get(SequenceNumbers.size() - 1);

                                    if (last_seq_num == next_seq_num) {
                                        isRetrasnmitting = false;
                                    }

                                    updateVarsSend(sendDataBytes, isData(sendDataBytes));

                                } else {

                                    // we have enough room to send a full data segment

                                    if (next_seq_num == 1 && !lastPacket) {
                                        // System.out.println("about to send the first packet");
                                        // the first packet of data we send
                                        // this packet has to include the file name and the file name size

                                        // byte[] data = new byte[dataSegmentSize]; // this is 500

                                        // // need to get the size of the file name
                                        // byte[] fileNameAsBytes = fileName.getBytes(); // this is 10

                                        // byte[] fileNameLengthBytes = new byte[4];
                                        // ByteBuffer buffer = ByteBuffer.wrap(fileNameLengthBytes);
                                        // buffer.putInt(fileNameAsBytes.length);

                                        // int fileDataLength = fileReader.read(data, 0,
                                        // dataSegmentSize - fileNameAsBytes.length - 4);
                                       

                                        // byte[] dataBytes = new byte[dataSegmentSize - fileNameAsBytes.length - 4];

                                        // // need to copy 0 to fileDatalenght into the dataBYtes buffer
                                        // System.arraycopy(data, 0, dataBytes, 0, fileDataLength);

                                        // byte[] dataPacket = new byte[fileDataLength + 4 + fileNameAsBytes.length];

                                        // if (dataPacket.length < dataSegmentSize){
                                        // lastPacket = true;
                                        // }

                                        // ByteBuffer packetBuffer = ByteBuffer.wrap(dataPacket);
                                        // packetBuffer.put(fileNameLengthBytes);
                                        // packetBuffer.put(fileNameAsBytes);
                                        // packetBuffer.put(dataBytes);

                                        // // now the dataPacket should contain all the
                                        // sendDataBytes = buildPacket(dataPacket, 1, next_seq_num);
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

                                        // TODO: the first packet could be the last

                                    } else {
                                        // we send more data but not the first byte
                                        byte[] dataToSend = new byte[dataSegmentSize];
                                        // in this case we can fill the entire segment with data

                                        int fileDataLength = fileReader.read(dataToSend, 0, dataSegmentSize);

                                        // check if this is the last data
                                        if (fileDataLength == -1) {
                                            sendDataBytes = buildPacket(new byte[0], 0, next_seq_num); // TODO: check
                                                                                                       // out curr
                                                                                                       // sequence
                                                                                                       // number
                                            // we send a blank packet with no data that will be dropped by the reciver
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
                                    // System.out.println("seq_num: " + pullSeqNum(sendDataBytes));
                                    // int length = pullLength(sendDataBytes); 
                                    // int actualLength = length >> 3;

                                    // System.out.println("amount data: " + actualLength);
                                    // System.out.println("ack: " + pullAck(sendDataBytes));

                                    server_socket.send(packet);
                                    num_packets_sent++;
                                    packets.put(next_seq_num, sendDataBytes);
                                    SequenceNumbers.add(next_seq_num);
                                    startTimer(next_seq_num);
                                    stateus.put(next_seq_num,
                                            new TCPState(prev_ack, next_seq_num, curr_seq_num, next_ack_num));
                                    printPacket(sendDataBytes, false);
                                    updateVarsSend(sendDataBytes, isData(sendDataBytes)); // now send the data

                                }
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

                try {
                    server_socket.receive(packet); // this will wait here
                   

                } catch (Exception e) {
                    System.out.println("Reciving packet threw error");
                }

                if (validateChecksum(data)) {

                    // need a way to pull out the ack number from the recived packet
                    int ackNumber = pullAck(data);

                    // System.out.println("seq_num: " + pullSeqNum(data));
                    // int length = pullLength(data); 
                    // int actualLength = length >> 3;

                    // System.out.println("amount data: " + actualLength);
                  



                    // case 1
                    synchronized (lock) {
                        if (ackNumber == next_seq_num) {
                            // this means we go an acknowlegement/data from the data we just sent
                            // get the destination name
                            printPacket(data, true);
                           
                            try {
                                name_dst_ip = InetAddress.getByName(destIP);
                            } catch (UnknownHostException e) {
                                System.out.println("Could not find the host");
                                System.exit(-1);
                            }
                            

                            // recived the ack so we can cancel the timer
                            cancelTimer(curr_seq_num);
                            // System.out.println("canceled timer");
                            updateVarsRec(data, isData(data));

                            byte[] returned_ack = packets.get(curr_seq_num); // gets the old packet back

                            if (returned_ack != null) {
                                // now we need to pull out the time
                                long start_time = pullTime(returned_ack);
                                recalculateTimeout(curr_seq_num, start_time);
                            }

                            if (isData(data)) { // this evaluates to true when we have a SYN or a fin
                                // System.out.println("here1"); 

                                if (isSYNFIN(data)) {
                                    curr_seq_num += 1;

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
                                // TODO: have to make sure retransmitted packets do not get timeout set



                            } else {
                                // TODO: need to acknowledge that the packet came back
                                // TODO: need to finsih 3 way handshake

                                if (lastPacket) {
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
                                    // next_seq_num++; // TODO: check this
                                    packets.put(next_seq_num, finData);
                                    SequenceNumbers.add(next_seq_num);
                                    startTimer(next_seq_num);
                                    printPacket(finData, false);

                                    updateVarsSend(finData, isData(finData));
                                   

                                }

                            }

                        } else {
                            printPacket(data, true);
                            int ackNum = pullAck(data); // 501
                            DuplicateAcks.add(ackNum); // add to duplicates
                            if (DuplicateAcks.size() >= 3 &&
                                    DuplicateAcks.get(DuplicateAcks.size() - 1)
                                            .equals(DuplicateAcks.get(DuplicateAcks.size() - 2))
                                    &&
                                    DuplicateAcks.get(DuplicateAcks.size() - 2)
                                            .equals(DuplicateAcks.get(DuplicateAcks.size() - 3))) {
                                resetState(ackNum);
                                DuplicateAcks = new ArrayList<>(); // clear out duplicate acks
                            }

                        }
                        // System.out.println("finished Reciever");
                    }
                } else {
                    // checksum was not valid
                    num_incorrect_checksums++;
                }
            }
        }

    }

    // The tcp state class
    public class TCPState {
        private int TCPState_prev_ack;
        private int TCPState_next_seq_num;
        private int TCPState_curr_seq_num;
        private int TCPState_next_ack_num;

        public TCPState(int TCPState_prev_ack, int TCPState_next_seq_num, int TCPState_curr_seq_num,
                int TCPState_next_ack_num) {
            this.TCPState_prev_ack = TCPState_prev_ack;
            this.TCPState_next_seq_num = TCPState_next_seq_num;
            this.TCPState_curr_seq_num = TCPState_curr_seq_num;
            this.TCPState_next_ack_num = TCPState_next_ack_num;
        }

        public int getTCPState_prev_ack() {
            return TCPState_prev_ack;
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
