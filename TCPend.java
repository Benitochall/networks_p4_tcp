import java.util.*;
import java.io.*;

public class TCPend {

    public static void printSummary(int amountDataSent, int num_packets_sent, int num_out_of_seq,
            int num_incorrect_checksums, int totalRetransmissions, int num_duplicate_acks) {

        System.out.println("Amount of Data transferred/received: " + amountDataSent + " bytes");

        System.out.println("Number of packets sent: " + num_packets_sent);

        System.out.println("Number of out-of-sequence packets discarded: " + num_out_of_seq);

        System.out.println("Number of packets discarded due to incorrect checksum: " + num_incorrect_checksums);

        System.out.println("Number of retransmissions: " + totalRetransmissions);

        System.out.println("Number of duplicate acknowledgements: " + num_duplicate_acks);

    }

    public static void main(String[] args) {

        int port = 0; // the port number on which the client will run
        int remote_port = 0;
        String remote_ip = null;
        String file_name = null;
        int mtu = 0; // max transmission unit in bytes
        int sws = 0; // the sliding window size in number of segments
        String file_path_name = null;

        // java TCPend -p <port> -s <remote IP> -a <remote port> f <file name> -m <mtu>
        // -c <sws>

        // first need to check if it is a sender or a receiver

        if (args.length == 12) {
            // this is the case where we have a sender
            for (int i = 0; i < args.length; ++i) {
                if (args[i].equals("-p")) {
                    // this is how we determine the port number
                    port = Integer.parseInt(args[++i]);
                    if (port < 0) {
                        System.out.println("Invalid port number");
                        return;
                    }

                } else if (args[i].equals("-s")) {
                    remote_ip = args[++i];

                } else if (args[i].equals("-a")) {

                    remote_port = Integer.parseInt(args[++i]);
                    if (remote_port < 0) {
                        System.out.println("Invalid port number");
                        return;
                    }

                } else if (args[i].equals("-f")) {

                    file_name = args[++i];

                } else if (args[i].equals("-m")) {

                    mtu = Integer.parseInt(args[++i]);

                } else if (args[i].equals("-c")) {
                    sws = Integer.parseInt(args[++i]);

                } else {
                    System.out.println(
                            "Sender Usage: java TCPend -p <port> -s <remote IP> -a <remote port> -f <file name> -m <mtu> -c <sws>");
                    return;
                }
                // in this case the args were properly set, and we can start the sender

            }
            SendHost send_host = new SendHost(port, remote_ip, remote_port, file_name, mtu, sws);
            System.out.println("Sender Stats:"); 
            printSummary(send_host.getDataTransfered(), send_host.getNumPacketsSent(),
                    0,
                    send_host.getIncorrectChecksums(),
                    send_host.getRetransmissions(), send_host.getDuplicateAcks());
            System.exit(0);
        }
        // java TCPend -p <port> -m <mtu> -c <sws> -f <file name>
        else if (args.length == 8) {
            // this is the case where we have a receiver
            for (int i = 0; i < args.length; ++i) {
                if (args[i].equals("-p")) {
                    // this is how we determine the port number
                    port = Integer.parseInt(args[++i]);
                    if (port < 0) {
                        System.out.println("Invalid port number");
                        System.out.println(port);
                        return;
                    }

                } else if (args[i].equals("-m")) {

                    mtu = Integer.parseInt(args[++i]);

                } else if (args[i].equals("-c")) {
                    sws = Integer.parseInt(args[++i]);

                } else if (args[i].equals("-f")) {
                    file_path_name = args[++i];
                } else {
                    System.out.println(
                            "Receiver Usage: java TCPend -p <port> -s <remote IP> -a <remote port> -f <file name> -m <mtu> -c <sws>");

                }
            }
            // invoke the receiverhost
            ReceiverHost receiver_host = new ReceiverHost(port, mtu, sws, file_path_name);

        } else {
            System.out.println(
                    "Sender Usage: java TCPend -p <port> -s <remote IP> -a <remote port> -f <file name> -m <mtu> -c <sws>");

            System.out.println(
                    "Receiver Usage: java TCPend -p <port> -s <remote IP> -a <remote port> -f <file name> -m <mtu> -c <sws>");

        }

    }

}
