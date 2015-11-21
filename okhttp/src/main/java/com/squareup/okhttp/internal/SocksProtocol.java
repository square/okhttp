package com.squareup.okhttp.internal;


import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Socket;

public class SocksProtocol {
    private SocksProtocol() {
    }

    public static final byte VERSION_5 = 5;
    public static final byte METHOD_NONE = (byte) 0xff;
    public static final byte METHOD_NO_AUTHENTICATION_REQUIRED = 0;
    public static final byte ADDRESS_TYPE_IPV4 = 1;
    public static final byte ADDRESS_TYPE_DOMAIN_NAME = 3;
    public static final byte COMMAND_CONNECT = 1;
    public static final byte REPLY_SUCCEEDED = 0;


    /**
     * Parses a SocksMsg from a stream.
     *
     * @param in DataInputStream to parse
     * @return reply byte of the message
     * @throws IOException       in case of a network error
     * @throws ProtocolException in case of unsupported responses
     */
    public static byte parseSocksMsg(DataInputStream in) throws IOException {
        byte ver = in.readByte();
        if (ver != VERSION_5)
            throw new ProtocolException("SOCKS5: unsupported version");
        byte response = in.readByte();
        in.readByte(); //reserved
        byte addrtype = in.readByte();
        switch (addrtype) {
            case (ADDRESS_TYPE_IPV4): //IPv4
                in.skipBytes(4);
                break;
            case (ADDRESS_TYPE_DOMAIN_NAME)://host name
                byte addressLen = in.readByte();
                in.skipBytes(addressLen);
                break;
            default:
                throw new ProtocolException();
        }
        in.skipBytes(2); //port
        return response;
    }


    /**
     * This method takes a socket and establishes a SOCKS5 connection to target.
     *
     * @param socket a (connected) socket to a proxy
     * @param target the targets
     * @throws IOException
     */
    public static void establish(Socket socket, InetSocketAddress target) throws IOException {
        // negotiate authentication
        byte[] cmd = new byte[3];
        cmd[0] = VERSION_5; // version
        cmd[1] = (byte) 0x01; // number of auth methods
        cmd[2] = METHOD_NO_AUTHENTICATION_REQUIRED; // no-authentication

        DataInputStream in = new DataInputStream(socket.getInputStream());
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        out.write(cmd);
        out.flush();

        byte[] resp = new byte[2];
        in.readFully(resp);

        // di we receive a valid answer?
        if (resp[0] != VERSION_5 || resp[1] != METHOD_NO_AUTHENTICATION_REQUIRED) {
            throw new ProtocolException("SOCKS5: unexpected answer: " + resp[0] + " " + resp[1]);
        }

        // connect to target
        byte[] connectionReq;
        connectionReq = createSocksConnectReq(target);
        out.write(connectionReq);
        out.flush();

        // receive response
        byte connectionResp;
        connectionResp = parseSocksMsg(in);
        if (connectionResp != REPLY_SUCCEEDED)
            throw new ProtocolException("Proxy responded with a failure:" + connectionResp);
    }


    /**
     * This method builds a request to connection to target.
     *
     * @param target the InetSocketAddress of the target, the hostname is resolved by the proxy
     * @return request data
     */
    static byte[] createSocksConnectReq(InetSocketAddress target) {
        byte[] addr = target.getHostName().getBytes();
        short port = (short) target.getPort();

        byte[] req = new byte[7 + addr.length];
        req[0] = VERSION_5; // version (SOCKS5)
        req[1] = COMMAND_CONNECT; // socks command
        req[2] = (byte) 0x00; // reserved
        req[3] = ADDRESS_TYPE_DOMAIN_NAME; // address type
        req[4] = (byte) addr.length; // address len
        System.arraycopy(addr, 0, req, 5, addr.length); // address
        req[req.length - 2] = (byte) ((port >> 8) & 0xFF); // address port
        req[req.length - 1] = (byte) (port & 0xFF);

        return req;
    }


}
