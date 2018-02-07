import java.io.*;
import java.net.*;
import java.util.concurrent.locks.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CSftp
{
    // static variables
    private static final String DEFAULT_FILENAME = "file";
    private static final int MAX_LEN = 255;
    private static final Lock lock = new ReentrantLock();
    private static final Condition msgNotReceived = lock.newCondition();
    private static String hostName;
    private static int portNum;

    // non-static variables
    private Socket mainSocket, receiveSocket;
    private PrintWriter out;
    private BufferedReader in, stdIn;
    private CmdSender cmdSender;
    private MsgReceiver msgReceiver;
    private boolean isMsgReceived = false;
    private String fileName = DEFAULT_FILENAME;
    private String cmdSent;

    // constructor which sets up connection and starts cmdSender and msgReceiver
    public CSftp(String hostName, int portNum) {
        try {
            mainSocket = new Socket();
            receiveSocket = new Socket();
            mainSocket.connect(new InetSocketAddress(hostName, portNum), 20000);
            out = new PrintWriter(mainSocket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(mainSocket.getInputStream()));
            stdIn = new BufferedReader(new InputStreamReader(System.in));
            msgReceiver = new MsgReceiver();
            cmdSender = new CmdSender();
            msgReceiver.run();
            cmdSender.run();
        } catch (IOException e) {
            System.out.printf("0xFFFC Control connection to %s on port %d failed to open.\n", hostName, portNum);
            System.exit(1);
        }
    }


    // send cmd to server, and wait for server's response before sending next cmd
    private class CmdSender extends Thread {
        public CmdSender() {
            super();
            start();
        }

        private void sendCmd(String cmd, String param) {
            String toSent;
            if (!param.equals(""))
                toSent = cmd + " " + param;
            else
                toSent = cmd;
            cmdSent = cmd;
            out.println(toSent);
            System.out.println("--> " + toSent);
        }

        private void waitMsg() throws InterruptedException{
            lock.lock();
            isMsgReceived = false;
            msgNotReceived.await();
            lock.unlock();
        }

        // command loop goes here
        @Override
        public void run() {
            String cmd;
            // only exit when "quit" is entered
            try {
                waitMsg();
            } catch (InterruptedException e){
                System.out.printf("0xFFFF Processing error. %s.\n", e.getMessage());
            }
            while (true) {
                try {
                    System.out.print("csftp> ");
                    cmd = stdIn.readLine();
                    if (cmd.length() > MAX_LEN) {
                        throw new IOException(); // check input cmd size
                    } else {
                        String[] cmdSplited = cmd.split("([\\s|\\t]+)"); // split the cmd by spaces or tabs
                        int len = cmdSplited.length;
                        // trim out the leading empty string
                        if (len == 0) {
                            cmdSplited = new String[1];
                            cmdSplited[0] = "";
                        } else if (len > 1 && cmdSplited[0].isEmpty()){
                            String[] temp = new String[len-1];
                            for (int i=0; i < len-1; i++)
                                temp[i] = cmdSplited[i+1];
                            cmdSplited = temp;
                        }
                        len = cmdSplited.length;
                        switch (cmdSplited[0]) {
                            case "user":
                                if (len != 2) throw new ArrayIndexOutOfBoundsException();
                                sendCmd("USER", cmdSplited[1]);
                                waitMsg();
                                break;
                            case "pw":
                                if (len != 2) throw new ArrayIndexOutOfBoundsException();
                                sendCmd("PASS", cmdSplited[1]);
                                waitMsg();
                                break;
                            case "quit":
                                if (len != 1) throw new ArrayIndexOutOfBoundsException();
                                sendCmd("QUIT", "");
                                waitMsg();
                                break;
                            case "get":
                                if (cmdSplited.length != 2) throw new ArrayIndexOutOfBoundsException();
                                sendCmd("PASV", "");
                                waitMsg();
                                sendCmd("TYPE I", "");
                                waitMsg();
                                fileName = cmdSplited[1];
                                if (receiveSocket.isConnected()) {
                                    sendCmd("RETR", cmdSplited[1]);
                                    waitMsg();
                                }
                                break;
                            case "features":
                                if (len != 1) throw new ArrayIndexOutOfBoundsException();
                                sendCmd("FEAT", "");
                                waitMsg();
                                break;
                            case "cd":
                                if (len != 2) throw new ArrayIndexOutOfBoundsException();
                                sendCmd("CWD", cmdSplited[1]);
                                waitMsg();
                                break;
                            case "dir":
                                if (len != 1) throw new ArrayIndexOutOfBoundsException();
                                sendCmd("PASV", "");
                                waitMsg();
                                if (receiveSocket.isConnected()) {
                                    sendCmd("LIST", "");
                                    waitMsg();
                                }
                                break;
                            case "":
                            case "#":
                                break;
                            default:
                                if (cmdSplited[0].matches("#(.*?)"))
                                    break;
                                System.out.println("0x001 Invalid command.");
                                break;
                        }
                    }
                } catch (IOException e) {
                    System.out.println("0xFFFE Input error while reading commands, terminating.");
                    System.exit(1);
                } catch (InterruptedException e) {
                    System.out.printf("0xFFFF Processing error. %s.\n", e.getMessage());
                } catch (ArrayIndexOutOfBoundsException e) {
                    System.out.println("0x002 Incorrect number of arguments.");
                }
            }
        }
    }


    // keep listening to server's responses, and signal the cmdSender to perform next iteration
    private class MsgReceiver extends Thread {
        public MsgReceiver() {
            super();
            start();
        }
        @Override
        public void run() {
            String msgReceived;
            try {
                while ((msgReceived = in.readLine()) != null) {
                    System.out.println("<-- " + msgReceived);
                    if (msgReceived.substring(0, 4).matches("\\d\\d\\d ")) {
                        String resCode = msgReceived.substring(0, 3);
                        switch (resCode) {
                            case "221":  // exit the client after receive resCode 221
                                System.exit(0);
                            case "150":
                                if (cmdSent.equals("LIST"))
                                    getList();
                                else if (cmdSent.equals("RETR"))
                                    receiveFile();
                                fileName = DEFAULT_FILENAME;
                                if (receiveSocket.isConnected())
                                    receiveSocket.close();
                                continue;
                            case "227":
                                setupReceiveSocket(msgReceived);
                                break;
                            case "421":
                            case "425":
                            case "426":
                            case "450":
                            case "451":
                            case "452":
                                mainSocket.close();
                                System.out.println("0xFFFD Control connection I/O error, closing control connection.");
                                System.exit(1);
                            default:
                                break;
                        }
                        lock.lock();
                        if (!isMsgReceived) {
                            isMsgReceived = true;
                            msgNotReceived.signal();
                        }
                        lock.unlock();
                    }
                }
            } catch (IOException e) {
                System.out.println("0xFFFD Control connection I/O error, closing control connection.");
                System.exit(1);
            }
        }

        private void getList() {
            try {
                BufferedReader list = new BufferedReader(new InputStreamReader(receiveSocket.getInputStream()));
                String line;
                while ((line = list.readLine()) != null)
                    System.out.println(line);
                list.close();
            } catch (IOException e) {
                System.out.println("0x3A7 Data transfer connection I/O error, closing data connection.");
            }
        }

        private void receiveFile() {
            int exceptionFlag = 0;
            try {
                DataInputStream inData = new DataInputStream(new BufferedInputStream(receiveSocket.getInputStream()));
                exceptionFlag = 1;
                byte[] buffer = new byte[1024];
                int len = inData.read(buffer);
                FileOutputStream outFile = new FileOutputStream(fileName);
                while (len != -1) {
                    outFile.write(buffer, 0, len);
                    len = inData.read(buffer);
                }
                inData.close();
                outFile.close();
            } catch (IOException e) {
                if (exceptionFlag == 0) {
                    System.out.println("0x3A7 Data transfer connection I/O error, closing data connection.");
                } else {
                    System.out.printf("0x38E Access to local file %s denied.\n", fileName);
                }
            }
        }

        private void setupReceiveSocket(String msgReceived) {
            Pattern pattern = Pattern.compile("\\d+,\\d+,\\d+,\\d+,\\d+,\\d+");
            Matcher matcher = pattern.matcher(msgReceived);
            if (matcher.find()) {
                String[] nums = matcher.group(0).split(",");
                int portNum = Integer.parseInt(nums[4]) * 256 + Integer.parseInt(nums[5]);
                String hostName = nums[0] + "." + nums[1] + "." + nums[2] + "." + nums[3];
                try {
                    receiveSocket = new Socket();
                    receiveSocket.connect(new InetSocketAddress(hostName, portNum), 10000);
                } catch (IOException e) {
                    System.out.printf("0x3A2 Data transfer connection to %s on port %d failed to open.\n", hostName, portNum);
                }
            } else System.out.println("0x3A2 Data transfer connection failed to open.");
        }
    }

    public static void main(String [] args)
    {
        // Get command line arguments and connected to FTP
        // If the arguments are invalid or there aren't enough of them
        // then exit with usage: cmd ServerAddress ServerPort

        try {
            if (args.length == 2) {
                hostName = args[0];
                portNum = Integer.parseInt(args[1]);
            } else if (args.length == 1) {
                hostName = args[0];
                portNum = 21;
            } else {
                System.out.println("usage: cmd ServerAddress ServerPort");
                System.exit(1);
            }
            new CSftp(hostName, portNum);
        } catch (NumberFormatException e) {
            System.out.println("Port number invalid!");
        }
    }
}