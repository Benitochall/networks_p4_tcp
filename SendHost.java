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
    private int curr_ack; // this is the current ack bytes
    private int next_seq_num; // this is the next sequence number that we are going to send
    private int curr_seq_num;
    private int next_ack_num; // this is the next ack we are going to send 

    private DatagramSocket server_socket;
    private InetAddress name_dst_ip;

    // a lock to protect the next_seq_num
    private final Object lock = new Object();

    // this is the host that will send data
    public SendHost(int port, String destIP, int destinationPort, String fileName, int mtu, int sws) {
        // the constructor
        this.port = port;
        this.destIP = destIP;
        this.dst_port = destinationPort;
        this.fileName = fileName;
        this.mtu = mtu;
        this.sws = sws;
        this.curr_ack = 0; // the first ack is always 0
        this.next_seq_num = 0;
        this.curr_seq_num = 0;
        this.next_ack_num = 0; // this is what we expect back when reciving data back

        // first thing that needs to be done is a socketopened
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

        // now we need to send out the first part of the three way handshake
        // need to build up the packet
        // we need the 29th bit to be a 1 because this will be a SYN

        // there is no data in the first packet

        // in binary we will represent all three flags as 7
        // just

        synchronized (lock) { // we cannot let other threads exectute while this is happening

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

            // now we need to update the next_sequence number becasue it should be 1
            next_seq_num++; // increments by 1

            // ok we just sent a packet with a sequence number of 0 and a current ack of 0
            // the next ack should come back as 1

        }

    }

    public byte[] buildPacket(byte[] data, int flags, int sequenceNumber) {

        // the first 4 bytes are the sequence numbrer
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
        length = length << 3;

        // TODO: I am not sure about this
        length |= flags & 0b111;

        byte[] lengthBytes = new byte[8];
        ByteBuffer buffer4 = ByteBuffer.wrap(lengthBytes);
        buffer4.putInt(length);

        // no the zeros field
        byte[] zeros = new byte[2];

        // TODO: need to fill in the checksum
        short checksum = 0;
        byte[] checkSumBytes = new byte[2];
        ByteBuffer buffer5 = ByteBuffer.wrap(checkSumBytes);
        buffer5.putShort(checksum);

        int totalLength = sequenceNumberBytes.length + currentAckBytes.length +
                timeStamp.length + lengthBytes.length + zeros.length + checkSumBytes.length + data.length;

        byte[] packet = new byte[totalLength];

        ByteBuffer packetBuffer = ByteBuffer.wrap(packet);
        packetBuffer.put(sequenceNumberBytes);
        packetBuffer.put(currentAckBytes);
        packetBuffer.put(timeStamp);
        packetBuffer.put(lengthBytes);
        packetBuffer.put(zeros);
        packetBuffer.put(checkSumBytes);
        packetBuffer.put(data);

        return packet;

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

    public void updateVars(byte[] packet, boolean isData) {
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
            // TODO

        }
        if (((length >> 1) & 1) == 1) { // FIN bit
            // TODO

        }
        if ((length & 1) == 1) { // ACK bit
            // TODO

        }

    }

    public class SendThread extends Thread {

        @Override
        public void run() {
            // here we need to run the sending data thread
            // need to constantly send data

        }

    }

    public class RecThread extends Thread {

        @Override
        public void run() {
            // here we need to run the reciveing data thread

            // first recieve the packet that is being sent. should be an ack and therefore
            // contain no data
            byte[] data = new byte[24];
            DatagramPacket packet = new DatagramPacket(data, 24); // data now stores the packet

            try {
                server_socket.receive(packet);

            } catch (Exception e) {
                System.out.println("Reciving packet threw error");
            }

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
                    updateVars(data, isData);

                    // now we need to send a packet back that we recived the data
                    // flags = 1 means the ACK flag is set
                    byte[] ackToSend = buildPacket(new byte[0], 1, curr_seq_num); // our sequence number should not
                                                                                  // change because we did not send any
                                                                                  // data

                    // this will build a packet that contains the new ack number

                    // now we neede to send the datagram
                    DatagramPacket ackDatagram = new DatagramPacket(ackToSend, ackToSend.length, name_dst_ip, dst_port);

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

                }

            } else {

                int ackNum = pullAck(data);
                if (ackNum < next_seq_num) { // we get an old ack, need to send our next sequence number with the old ack
                    next_seq_num = ackNum;
                }
            }

        }

    }

}
