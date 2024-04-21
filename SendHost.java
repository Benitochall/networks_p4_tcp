import java.net.*;
import java.net.InetAddress; 
import java.nio.ByteBuffer;

public class SendHost {
    
    // all the instance vars 
    private int send_port; 
    private String destIP;
    private int recv_port; 
    private String fileName;
    private int mtu; 
    private int sws; 
    private int curr_ack; // this is the current ack bytes
    private int next_seq_num; 


    private DatagramSocket server_socket; 
    private InetAddress name_dst_ip; 

    // a lock to protect the next_seq_num
    private final Object lock = new Object();



    // this is the host that will send data 
    public SendHost(int port, String destIP, int destPort, String fileName, int mtu, int sws){
        // the constructor
        this.send_port = port; 
        this.destIP = destIP; 
        this.recv_port = destPort; 
        this.fileName = fileName; 
        this.mtu = mtu; 
        this.sws = sws; 
        this.curr_ack = 0; // the first ack is always 0 
        this.next_seq_num = 0; 




        // first thing that needs to be done is a socketopened 
        try {
            this.server_socket = new DatagramSocket(this.send_port);

        }
        // opens up a socket on the port
        catch (SocketException e){
            System.exit(-1); 
        }

        // ok now that the server is accepting packets on this port we need to start some threads 

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


            byte [] data = buildPacket(new byte[0], 4, 0); 

            // then we need to set this as a UDP datagram
            try {
                name_dst_ip = InetAddress.getByName(destIP); 
            }
            catch (UnknownHostException e){
                System.out.println("Could not find the host"); 
                System.exit(-1); 
            }

            DatagramPacket packet = new DatagramPacket(data, data.length, name_dst_ip, destPort); 

            try {
                server_socket.send(packet); 
            }

            catch (Exception e){
                System.out.println("Sending threw error"); 
                System.exit(-1); 
            }
            // send the packet

            // now we need to update the next_sequence number becasue it should be 1
            next_seq_num++; // increments by 1 


        }



    }

    public byte[] buildPacket(byte[] data, int flags, int sequenceNumber){

        // the first 4 bytes are the sequence numbrer 
        byte [] sequenceNumberBytes = new byte[4]; 
        ByteBuffer buffer = ByteBuffer.wrap(sequenceNumberBytes); 
        buffer.putInt(sequenceNumber); 

        // the next 4 bytes are the current ack intially 0 
        byte [] currentAckBytes = new byte[4]; 
        ByteBuffer buffer2 = ByteBuffer.wrap(currentAckBytes); 
        buffer2.putInt(this.curr_ack); 

        // now to do the timestamp
        byte [] timeStamp = new byte[8]; 
        ByteBuffer buffer3 = ByteBuffer.wrap(timeStamp); 
        long currTimeStamp = System.nanoTime();
        buffer3.putLong(currTimeStamp); 

        // now to do the length field
        int length = data.length; // this should be 0 initially 

        // now to make room for the flag bits 
        length = length << 3; 

        //TODO: I am not sure about this 
        length |= flags & 0b111;

        byte [] lengthBytes = new byte[8]; 
        ByteBuffer buffer4 = ByteBuffer.wrap(lengthBytes); 
        buffer4.putInt(length); 

        // no the zeros field
        byte[] zeros = new byte[2]; 

        // TODO: need to fill in the checksum
        short checksum = 0; 
        byte [] checkSumBytes = new byte[2]; 
        ByteBuffer buffer5 = ByteBuffer.wrap(checkSumBytes); 
        buffer5.putShort(checksum); 

        int totalLength = sequenceNumberBytes.length + currentAckBytes.length +
            timeStamp.length + lengthBytes.length + zeros.length + checkSumBytes.length + data.length;

        byte [] packet = new byte[totalLength]; 

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

    public class SendThread extends Thread {

        @Override
        public void run() {
            // here we need to run the sending data thread

        }


    }

    public class RecThread extends Thread {

        @Override
        public void run() {
            // here we need to run the reciveing data thread

        }


    }


}
