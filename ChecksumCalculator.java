public class ChecksumCalculator {
    public static byte [] calculateChecksum(byte[] data) {
        int sum = 0;
        int length = data.length;

        if (length % 2 != 0) {
            length++;
            byte[] paddedData = new byte[length];
            System.arraycopy(data, 0, paddedData, 0, data.length);
            paddedData[length - 1] = 0x00; 
            data = paddedData;
        }

        for (int i = 0; i < length; i += 2) {
            int segment = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            sum += segment;
        }

        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }

        short checksum = (short) (~sum & 0xFFFF);
        byte[] checksumBytes = new byte[2];
        checksumBytes[0] = (byte) ((checksum >> 8) & 0xFF);
        checksumBytes[1] = (byte) (checksum & 0xFF);
        return checksumBytes;
    }

    public static void main(String[] args) {
        byte[] data = { (byte) 0x86, (byte) 0x5E, (byte) 0xAC, (byte) 0x60, (byte) 0x71, (byte) 0x2A, (byte) 0x81, (byte) 0xB5};

        byte [] checksum = calculateChecksum(data);
        for (byte b : checksum) {
            System.out.printf("%02X", b);
        }
        System.out.println();
    }
}
