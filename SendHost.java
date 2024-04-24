import java.net.*;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.io.*;

public class SendHost {

    // all the instance vars
    private int port;
    private String destIP;
    private int dst_port;
    private String fileName;
    private int mtu;
    private int sws;
    private int prev_ack; // this is the last received acc
    private int next_seq_num; // this is the next sequence number that we are going to send
    private int curr_seq_num; // this is the current sequence number we are on,
    private int next_ack_num; // this is the next byte we expect from the sender
    private boolean foundHost;
    private int slidingWindowSize;

    private DatagramSocket server_socket;
    private InetAddress name_dst_ip;
    private boolean lastPacket;

    // a lock to protect the next_seq_num
    private final Object lock = new Object();

    private int dataSegmentSize;
    private int num_incorrect_checksums;

    private long timeout = 5L * 1_000_000_000L;

    // this is the host that will send data
    public SendHost(int port, String destIP, int destinationPort, String fileName, int mtu, int sws) {
        // the constructor
        this.port = port;
        this.destIP = destIP;
        this.dst_port = destinationPort;
        this.fileName = fileName;
        this.mtu = mtu;
        this.sws = sws;
        this.prev_ack = -1; // the first ack is always 0
        this.next_seq_num = 0;
        this.curr_seq_num = 0;
        this.next_ack_num = 0; // this is what we expect back when receiving data back
        this.foundHost = false;
        this.slidingWindowSize = sws;
        this.dataSegmentSize = mtu - 24; // this is the maximum amount of data we can send across the network at a time
        // this is because we need space for header
        this.lastPacket = false;

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

        RecThread reciever_thread = new RecThread();
        SendThread sender_thread = new SendThread();

        // start threads
        reciever_thread.start();
        sender_thread.start();

        // now we need to send out the first part of the three-way handshake
        // need to build up the packet
        // we need the 29th bit to be a 1 because this will be a SYN

        // there is no data in the first packet

        // in binary, we will represent all three flags as 7
        // just

        synchronized (lock) { // we cannot let other threads execute while this is happening

            byte[] data = buildPacket(new byte[0], 4, 0);

            // then we need to set this as a UDP datagram
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
            // send the packet

            // now we need to update the next_sequence number because it should be 1
            next_seq_num++; // increments by 1

            // ok we just sent a packet with a sequence number of 0 and a current ack of 0
            // the next ack should come back as 1

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

    public int pullAck(byte[] packet) {

        ByteBuffer buffer = ByteBuffer.wrap(packet);
        buffer.position(4); // move buffer ahead

        return buffer.getInt();

    }

    public int pullSeqNum(byte[] packet) {

        ByteBuffer buffer = ByteBuffer.wrap(packet);
        buffer.position(0); // move buffer ahead

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

    public void updateVarsRec(byte[] packet, boolean isData) {
        // so if the data message that comes in has data
        int packet_seq_num = pullSeqNum(packet); // gets the sequence number out of the packet
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
            foundHost = true;

        }
        if (((length >> 1) & 1) == 1) { // FIN bit
            // now we have ended the connection after reciveing a fin back
            foundHost = false;

        }
        if ((length & 1) == 1) { // ACK bit
            // TODO

        }

    }

    public boolean validateChecksum(byte[] data) {

        // first check the acutal length of the data
        int length = pullLength(data);
        int actualLength = length >> 3;
        actualLength = actualLength + 24;

        // create a new packet to validate checksum
        byte[] usefulData = new byte[actualLength]; // TODO: check this

        System.arraycopy(data, 0, usefulData, 0, actualLength); // copies the unbounded lenth of data into

        // now we have to pull out the checksum
        short checksum = pullChecksum(usefulData);

        // now that we got the checksum need to calculate it of the packet
        short calculatedChecksum = calculateChecksum(usefulData);

        return checksum == calculatedChecksum;
    }

    public class SendThread extends Thread {

        @Override
        public void run() {
            // here we need to run the sending data thread
            // need to constantly send data
            try {
                FileInputStream fileReader = new FileInputStream(new File(fileName));

                // we con only send a message of the size of the window
                byte[] sendDataBytes = new byte[slidingWindowSize];

                if (foundHost) { // we have recived a message from the host

                    // need to check the size of the sliding window
                    // the end of the sliding window prev_ack +
                    if (next_seq_num + dataSegmentSize < prev_ack + slidingWindowSize + 1) {

                        synchronized (lock) {

                            name_dst_ip = InetAddress.getByName(destIP);

                            // we have enough room to send a full data segment

                            if (next_seq_num == 1 && !lastPacket) {
                                // the first packet of data we send
                                // this packet has to include the file name and the file name size

                                byte[] data = new byte[dataSegmentSize];

                                // need to get the size of the file name
                                byte[] fileNameAsBytes = fileName.getBytes();

                                byte[] fileNameLengthBytes = new byte[4];
                                ByteBuffer buffer = ByteBuffer.wrap(fileNameLengthBytes);
                                buffer.putInt(fileNameAsBytes.length);

                                int fileDataLength = fileReader.read(data, 0,
                                        dataSegmentSize - fileNameAsBytes.length - 4);

                                byte[] dataBytes = new byte[dataSegmentSize - fileNameAsBytes.length - 4];

                                // need to copy 0 to fileDatalenght into the dataBYtes buffer
                                System.arraycopy(data, 0, dataBytes, 0, fileDataLength);

                                byte[] dataPacket = new byte[fileDataLength + 4 + fileNameAsBytes.length];

                                ByteBuffer packetBuffer = ByteBuffer.wrap(dataPacket);
                                packetBuffer.put(fileNameLengthBytes);
                                packetBuffer.put(fileNameAsBytes);
                                packetBuffer.put(dataBytes);

                                // now the dataPacket should contain all the
                                sendDataBytes = buildPacket(dataPacket, 0, next_seq_num);

                            } else {
                                // we send more data but not the first byte
                                byte[] dataToSend = new byte[dataSegmentSize];
                                // in this case we can fill the entire segment with data

                                int fileDataLength = fileReader.read(dataToSend, 0, dataSegmentSize);

                                // check if this is the last data
                                if (fileDataLength == -1) {
                                    sendDataBytes = buildPacket(new byte[0], 0, curr_seq_num);
                                    // we send a blank packet with no data that will be dropped by the reciver
                                    lastPacket = true;
                                } else {
                                    // this is the case where we have valid data
                                    byte[] data = new byte[dataSegmentSize - fileDataLength];
                                    System.arraycopy(dataToSend, 0, data, 0, fileDataLength); // copys the datatosend
                                                                                              // into
                                                                                              // data

                                    // check
                                    if (dataSegmentSize > data.length) {
                                        lastPacket = true;
                                    }
                                    sendDataBytes = buildPacket(data, 0, next_seq_num);

                                }

                            }
                            // now send the data
                            DatagramPacket packet = new DatagramPacket(sendDataBytes, sendDataBytes.length, name_dst_ip,
                                    dst_port);
                            server_socket.send(packet);
                            updateVarsSend(sendDataBytes, true);

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
            // here we need to run the reciveing data thread

            // first recieve the packet that is being sent. should be an ack and therefore
            // contain no data
            byte[] data = new byte[mtu];
            DatagramPacket packet = new DatagramPacket(data, mtu); // data now stores the packet

            try {
                server_socket.receive(packet);

            } catch (Exception e) {
                System.out.println("Reciving packet threw error");
            }

            if (validateChecksum(data)) {

                // need a way to pull out the ack number from the recived packet
                int ackNumber = pullAck(data);
                int seqNumberPacket = pullSeqNum(data); // this should = the next epected ack

                // this ack number should be equal to the next sequence number of for the first
                // case 1
                if (ackNumber == next_seq_num || (isData(data) && seqNumberPacket == next_ack_num)) {
                    // this means we go an acknowlegement/data from the data we just sent
                    // get the destination name
                    try {
                        name_dst_ip = InetAddress.getByName(destIP);
                    } catch (UnknownHostException e) {
                        System.out.println("Could not find the host");
                        System.exit(-1);
                    }

                    if (isData(data)) {
                        // this is where we get data back from the reciever
                        // ok now we need to update the next ack etc
                        // we have now just recived data from the incoming packet
                        boolean isData = true;
                        updateVarsRec(data, isData);

                        // now we need to send a packet back that we recived the data
                        // flags = 1 means the ACK flag is set
                        byte[] ackToSend = buildPacket(new byte[0], 1, curr_seq_num); // our sequence number should not
                                                                                      // change because we did not send
                                                                                      // any
                                                                                      // data

                        // this will build a packet that contains the new ack number

                        // now we neede to send the datagram
                        DatagramPacket ackDatagram = new DatagramPacket(ackToSend, ackToSend.length, name_dst_ip,
                                dst_port);

                        try {
                            server_socket.send(ackDatagram); // send the packet

                        } catch (IOException e) {
                            System.out.println("Failed to send packet");
                            System.exit(-1);
                        }

                        // and eventually send back data

                    } else {
                        // this is where we just get an ack back
                        // ackNumber == next_seq_num the ack recevied is == to the next sequence number
                        // we are going to send
                        // TODO: figure out what happens here

                        // after we are done sendind data we need to send a FIN
                        prev_ack = ackNumber -1; // we did not recive any data so the next ack will be the same 
                        // we have send all our data and need to send our closing fin on the other side

                        if (lastPacket){
                            // need to send a FIN packet to the server
                            byte[] finData = buildPacket(new byte[0], 2, next_seq_num); 
                            DatagramPacket finDatagramPacket = new DatagramPacket(finData, finData.length, name_dst_ip, dst_port); 
                            try {
                                server_socket.send(finDatagramPacket);

                            }
                            catch (Exception e) {
                                System.out.println("Failed to send FIN packet"); 
                                System.exit(-1); 
                            }

                            updateVarsSend(finData, true); 

                        }


                    }

                } else {
                    // TODO: deal with duplicate ACKS

                    int ackNum = pullAck(data);
                    if (ackNum < next_seq_num) { // we get an old ack, need to send our next sequence number with the
                                                 // old
                                                 // ack
                        next_seq_num = ackNum;
                    }
                }
            } else {
                // checksum was not valid
                num_incorrect_checksums++; 
            }

        }

    }

}
