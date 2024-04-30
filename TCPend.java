
/*
 * This class will set up our program to run the sending host and the receiving host
 */

import java.util.*; 
import java.io.*; 


public class TCPend {

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
                    if (port < 0){
                        System.out.println("Invalid port number");
                        return;
                    }

                } else if (args[i].equals("-s")) {
                    remote_ip = args[++i];

                } else if (args[i].equals("-a")) {

                    remote_port = Integer.parseInt(args[++i]);
                    if (remote_port < 0){
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
            System.out.println("Args " + port + " " + remote_ip + " " + file_name + " " + remote_ip + " " + mtu + " " + sws); 
        }
        // java TCPend -p <port> -m <mtu> -c <sws> -f <file name>
        else if (args.length == 8) {
            // this is the case where we have a receiver
            for (int i = 0; i < args.length; ++i) {
                if (args[i].equals("-p")) {
                    // this is how we determine the port number
                    port = Integer.parseInt(args[++i]);
                    if (port < 0 ){
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
            System.out.println("Args " + port +" " + mtu + " " + sws + " " + file_path_name); 

        }
        else {
            System.out.println(
                "Sender Usage: java TCPend -p <port> -s <remote IP> -a <remote port> -f <file name> -m <mtu> -c <sws>");
            
            System.out.println(
                "Receiver Usage: java TCPend -p <port> -s <remote IP> -a <remote port> -f <file name> -m <mtu> -c <sws>");

        }

    }

}
