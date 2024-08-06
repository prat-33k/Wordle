package src;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.Socket;
import java.util.Properties;

public class GameManager implements Runnable {
    private BufferedReader fromClient;
    private PrintWriter toClient;

    private AccountList accountList; // account list shared between threads
    private TemporaryList tempList; // temporary data list shared between threads
    private String username; // the logged user's username
    private boolean isLogged = false;
    private Properties prop;

    public GameManager(Socket socket, Properties properties, TemporaryList tempList,
            AccountList accountList)
            throws IOException {
        this.prop = properties;
        this.tempList = tempList;
        this.accountList = accountList;
        fromClient = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        toClient = new PrintWriter(socket.getOutputStream(), true);
    }

    private synchronized boolean register(String username, String password) {
        if (accountList.getAccount(username) != null) {
            toClient.println("ERROR - this username is registered already. Please login.");
            return false;
        } else {
            accountList.add(new Account(username, password));
            toClient.println("OK - user successfully registered! Please login.");
            return true;
        }
    }

    private synchronized boolean login(String username, String password, int guessLimit) throws IOException {
        if (accountList.isRegistered(username, password)) {
            int index = tempList.getIndex(username);
            if (index != -1) {
                TemporaryData x = tempList.get(index);
                if (x.isLogged) {
                    toClient.println("ERROR - user is already logged. Retry.");
                    return false;
                }
                x.isLogged = true;
                this.username = username;
                this.isLogged = true;
                toClient.println("OK - login successful!");
                return true;
            }
            tempList.add(new TemporaryData(username, guessLimit));
            this.username = username;
            this.isLogged = true;
            toClient.println("OK - login successful!");
            return true;
        } else {
            toClient.println("ERROR - invalid username/password. Retry.");
            return false;
        }
    }

    private synchronized boolean logout() throws IOException {
        int index = tempList.getIndex(this.username);
        if (index != -1) {
            TemporaryData x = tempList.get(index);
            x.isLogged = false;
            this.isLogged = false;
            toClient.println("OK - logout successful. Bye!");
            return true;
        } else {
            toClient.println("ERROR - user is not logged in.");
            return false;
        }
    }

    public void run() {
        String message;
        int guessLimit = Integer.parseInt(this.prop.getProperty("guessLimit"));
        // managing requests in authentication process
        try {
            while (!this.isLogged) {
                message = fromClient.readLine();
                String[] data = message.split(",");
                switch (data[0]) {
                    case "register":
                        register(data[1], data[2]);
                        break;
                    case "login":
                        if (login(data[1], data[2], guessLimit)) {
                            toClient.println(true);
                            this.isLogged = true;
                        } else
                            toClient.println(false);
                        break;
                    case "exit":
                        System.out.println("Client disconnected.");
                        return;
                    default:
                        toClient.println("Invalid command.");
                        break;
                }
            }

            // setup multicast settings
            int portMulticast = Integer.parseInt(prop.getProperty("port_multicast"));
            int TTL = Integer.parseInt(prop.getProperty("time_to_live"));
            String addressMulticast = prop.getProperty("address_multicast");

            // entering multicast group
            InetAddress multiAddr = InetAddress.getByName(addressMulticast);
            MulticastSocket multiSocket = new MulticastSocket(portMulticast);
            multiSocket.setTimeToLive(TTL);
            multiSocket.joinGroup(multiAddr);

            // mananging requests in game process
            while (this.isLogged) {
                message = fromClient.readLine();
                String[] data = message.split(",");
                switch (data[0]) {
                    case "guess":
                        tempList.decrementGuesses(this.username);
                        String word = Server.word, hint = new String();
                        for (int i = 0; i < word.length(); i++) {
                            if (word.charAt(i) == data[1].charAt(i))
                                hint = hint + "+  ";
                            else if (word.indexOf(data[1].charAt(i)) != -1)
                                hint = hint + "?  ";
                            else
                                hint = hint + "X  ";
                        }
                        toClient.println(hint);
                        if (word.contentEquals(data[1]))
                            toClient.println(true);
                        else
                            toClient.println(false);
                        break;
                    case "won":
                        accountList.hasWon(this.username, Integer.parseInt(data[1]));
                        tempList.hasWon(this.username);
                        break;
                    case "lost":
                        accountList.hasLost(this.username, guessLimit);
                        tempList.hasLost(this.username);
                        break;
                    case "info":
                        toClient.println(tempList.getGuesses(this.username) + "," + tempList.hasGuessed(this.username));
                        break;
                    case "stats":
                        Account x = accountList.getAccount(this.username);
                        toClient.println(x.username + "," + x.numberOfMatches + "," + x.numberOfWins + ","
                                + x.currentWinStreak + "," + x.maxWinStreak + "," + x.averageTries);
                        break;
                    case "share":
                        String notification = data[1] + " has guessed the word '" + data[2] + "' with " + data[3]
                                + " attempts.";
                        DatagramPacket dp = new DatagramPacket(notification.getBytes(), notification.length(),
                                multiAddr, portMulticast);
                        multiSocket.send(dp);
                        break;
                    case "logout":
                        if (logout())
                            toClient.println(true);
                        else
                            toClient.println(false);
                        break;
                }
            }
        } catch (IOException e) {
            System.err.println("Client forced to disconnect.");
            if (this.isLogged) {
                try {
                    logout();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }
        }
    }
}