# TCP Implementation

This project implements TCP functionality over UDP in Java. It provides reliable data transmission over a UDP socket, incorporating features such as reliability through packet retransmission upon loss, data integrity via checksums, connection management using SYN and FIN standards, and optimization including fast retransmit.

## SenderHost Class

The `SenderHost` class manages the main functionality. It utilizes two threads: one for sending data packets and another for receiving acknowledgments (ACKs) or FIN messages from the receiver (`ReceiverHost`). Flow control ensures the receiver is not overwhelmed by setting the receiver buffer size in units of MTU. The sender waits for ACKs within the sliding window before sending new packets.

Packets sent by `SenderHost` include a header with sequence number, acknowledgment number, timestamp, data length, SYN/FIN/ACK flags, and a checksum for data integrity. The send operation continues across a specified port.

## ReceiverHost Class

The `ReceiverHost` class accepts incoming packets on a specified port and writes the received data to a specified file.

## Reliability

Each packet includes a timestamp, and a retry timer starts upon sending. If an ACK is not received within a set time, the packet is considered lost and resent. Exponential backoff is implemented for retransmissions, with a maximum of 16 retries before the connection is closed.

## Data Integrity

Every packet is equipped with a checksum. Upon reception, the receiver validates the checksum against a newly calculated one. Packets with checksum mismatches are dropped.

## Connection Management

The initial connection setup follows a three-way handshake (SYN - SYN-ACK - ACK). After transmitting the file, the sender initiates connection teardown with a FIN, awaits a FIN-ACK from the receiver, and finally sends an ACK to complete the closure.

## Optimizations

Fast retransmit triggers upon receiving three duplicate ACKs, facilitating quicker recovery from packet loss. A list of the last four sent packets is maintained to reset program state variables upon fast retransmit scenarios. The receiver also tracks received packets to prevent duplicates.

# Usage

The `TCPend` class initializes either sender or receiver modes based on the following arguments:

For sending mode:
java TCPend -p `<port>` -s `<remote IP>` -a `<remote port>` -f `<file name>` -m `<mtu>` -c `<sws>`
- `port`: Port number of the client
- `remote IP`: IP address of the remote peer
- `remote port`: Port of the remote receiver
- `file name`: File to be sent
- `mtu`: Maximum transmission unit in bytes
- `sws`: Sliding window size in segments

For receiving mode:
java TCPend -p `<port>` -f `<file name>` -m `<mtu>` -c `<sws>`
- `port`: Port number where the receiver listens
- `file name`: Path to save the incoming file
- `mtu`: Maximum transmission unit in bytes
- `sws`: Sliding window size in segments
