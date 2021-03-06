package rpc;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import cluster.ClusterMembership;
import cluster.Infrastructure;
import cookie.LocationMetadata;
import session.MySession;

public class Client {
	
	static int count = -1;
	private final static int operationSESSIONREAD = 1;
	private final static int operationSESSIONWRITE = 2;
	private final static int portProj1bRPC = 5300;
	private final static String DELIMITER = "_";
	private final static int TIMEOUT = 1000;
	public  final static int MAX_PACKET_SIZE = 4096;
	
	public String[] sessionRead(String sessionId, LocationMetadata locationData, Integer versionNumber) {
		
		System.out.println("RPC Client: Reading session data");  
		
		try {
			//Generating a unique id for this call/request to ignore responses
			//to stale requests
			String callID = UUID.randomUUID().toString();
			
			DatagramSocket rpcSocket = new DatagramSocket();
			List<String> ipAddress = locationData.getWqaddress();
			rpcSocket.setSoTimeout(TIMEOUT);
			
			//Sending read request to other RPC servers
			sendReadRequest(sessionId, callID, rpcSocket, ipAddress, versionNumber);
			
			//Wait for response from one of the RPC servers
			DatagramPacket recvPkt = getResponseForReadRequest(callID, rpcSocket, ipAddress);
			
			if(recvPkt != null){
				String output = new String(recvPkt.getData()).trim();
				System.out.println("RPC Client: Received packet with data: "+output);
				String[] session_params = output.split(DELIMITER);
				return session_params;
			} else {
				System.out.println("RPC Client: No packet was received");
				return new String[0];
			}
			
		} catch (Exception e) {
			e.printStackTrace();
		}
		System.out.println("RPC Client: No packet was received");
		return new String[0];
	}
	
	/**
	 * Sends the read request to those ip addresses that are present in the 
	 * cookie
	 */
	private void sendReadRequest(String sessionId, String callID, DatagramSocket 
			rpcSocket, List<String> ipAddress, Integer versionNumber) throws UnknownHostException, IOException {
		
		//Fill outBuf with [ callID, operationSESSIONREAD, sessionID, version ]
		String obuf = callID + DELIMITER + operationSESSIONREAD + DELIMITER 
				+  sessionId + DELIMITER + versionNumber;
		byte[] outBuf = obuf.getBytes();
		System.out.println("RPC Client: Generated the data that is to be sent: "+obuf);
		
		//Getting the addresses of the instances that have the required data
		//and sending the request to all of them
		System.out.println("Choosing random servers to send the read request");
		List<String> destionationIP = getRandomDestionations(ipAddress, Infrastructure.getR());
		for(String ami: destionationIP){
			String destIp = ClusterMembership.getIPFromAMI(Integer.valueOf(ami));
			System.out.println("RPC Client: Sent packet to "+destIp);
			DatagramPacket sendPkt = new DatagramPacket(outBuf, outBuf.length, 
					InetAddress.getByName(destIp), portProj1bRPC);
			rpcSocket.send(sendPkt);
		}	
	}
	
	/**
	 * Returns the packet it receives from the first successful response
	 */
	private DatagramPacket getResponseForReadRequest(String callID, DatagramSocket 
			rpcSocket, List<String> ipAddress) {
		
		//Waiting for the first successful response and exiting
		int responses = 0;
		int numServers = ipAddress.size();
		
		while (responses <= numServers) {
			byte[] inBuf = new byte[MAX_PACKET_SIZE];
			DatagramPacket recvPkt = new DatagramPacket(inBuf, inBuf.length);
			String[] responseParams = null;

			try {
				do {
					//Receive datagram packet and check if the packet is for the 
					//current call id
					recvPkt.setLength(inBuf.length);
					rpcSocket.receive(recvPkt);
					inBuf = recvPkt.getData();
					
					if (inBuf != null) {
						String response = new String(recvPkt.getData()).trim();
						System.out.println("RPC Client: Received a response: "+response);
						responseParams = response.split(DELIMITER);
					}
				} while (responseParams == null || !responseParams[0].equals(callID));
				
				System.out.println("RPC Client: Received a successful response");
				
			} catch (SocketTimeoutException stoe) {
				stoe.printStackTrace();
				responses++; //TODO: Remove this later
				recvPkt = null;
				continue;
			} catch (IOException ioe) {
				ioe.printStackTrace();
				responses++;
				recvPkt = null;
				ioe.printStackTrace();
				continue;
			}

			// If we reach this point, it means the call id in the response
			// params matches the call id we sent out
			System.out.println("RPC Client: Received a packet from one of the instance");
			rpcSocket.close();
			return recvPkt;
		}
		return null;
	}
	
	public List<String> sessionWrite(MySession session) {
		
		List<String> wqAddress = new ArrayList<String>();
		String sessionId = session.getSessionID(); 
		int version = session.getVersionNumber(); 
		String data = session.getMessage(); 
		Date discardTime = session.getExpirationDate();
		
		try {
			//Generating a unique id for this call/request to ignore responses
			//to stale requests
			DatagramSocket rpcSocket = new DatagramSocket();
			String callID = UUID.randomUUID().toString();
			
			//Fill outBuf with [ callID, operationSESSIONWRITE, sessionID, version, data, discard time ]
			String obuf = callID + DELIMITER + operationSESSIONWRITE + DELIMITER 
					+  sessionId + DELIMITER + version + DELIMITER + data 
					+ DELIMITER + discardTime;
			byte[] outBuf = obuf.getBytes();
			System.out.println("RPC Client: Sending "+obuf);
			
			//Send the packet out to all the members and wait for WQ successful 
			//responses 
			System.out.println("RPC Client: Sending write request to other instances");
			List<String> destinationIPAddresses = ClusterMembership.getMemberIPAddress();
			int numServers = destinationIPAddresses.size();
			List<String> wChosenDestionationIP = getRandomDestionations(destinationIPAddresses, Infrastructure.getW());
			
			System.out.println("RPC Client: Chose " + wChosenDestionationIP.size() + " instances out of "+destinationIPAddresses.size() + " at random");
			for(String destIp: wChosenDestionationIP){
				System.out.println("RPC Client: sending to "+destIp);
				DatagramPacket sendPkt = new DatagramPacket(outBuf, outBuf.length, 
						InetAddress.getByName(destIp), portProj1bRPC);
				rpcSocket.send(sendPkt);
			}
			
			//Continue probing for successful responses until we get enough
			//successes or we run out of servers 
			System.out.println("RPC Client: waiting for responses from WQ instances");
			int responses = 0;
			int successfulResponses = 0;
			while(successfulResponses < Infrastructure.getWQ() && responses < numServers){
				byte [] inBuf = new byte[MAX_PACKET_SIZE];
				DatagramPacket recvPkt = new DatagramPacket(inBuf, inBuf.length);
				String[] responseParams = null;
				
				//Keep probing until we get a packet that has the matching call id
				//that we had sent out for this request
				try {
					do {
						recvPkt.setLength(inBuf.length);
						rpcSocket.receive(recvPkt);
						if (inBuf != null) {
							String response = new String(inBuf).trim();
							System.out.println("RPC Client: Received a response: "+response);
							responseParams = response.split(DELIMITER); 
						}
					} while(responseParams == null || !responseParams[0].equals(callID));
					
					String ip = recvPkt.getAddress().toString().substring(1);
					Integer ami = ClusterMembership.getAMIFromIP(ip);
					if(ami != null){
						wqAddress.add(ami.toString());
						successfulResponses++;
						System.out.println("RPC Client: Received a successful response "
								+ "from IP: " + ip + " and AMI: " + ami +". Count = "+successfulResponses);
					}
				} 
				catch(SocketTimeoutException stoe) {
					stoe.printStackTrace();
					responses++;
				    recvPkt = null; 
				    continue; //Not sure if we need to catch a socket timeout in this implementation
				  } catch(IOException ioe) {
				    ioe.printStackTrace();
				    rpcSocket.close(); //IOException leads to exit with a false flag
				    return wqAddress;
				  }
				
				//We get to this point only if a response was successful
				responses++;
			}
			System.out.println("RPC Client: Consensus has been received");
			rpcSocket.close();
			return wqAddress;
		} catch(Exception e) {
			e.printStackTrace();
			return wqAddress;
		}
	}
	
	private List<String> getRandomDestionations(List<String> destinationIPAddresses, Integer R) {
		long seed = System.nanoTime();
		Collections.shuffle(destinationIPAddresses, new Random(seed));
		return destinationIPAddresses.subList(0,R);
	}

	public static int getOperationsessionread() {
		return operationSESSIONREAD;
	}

	public static int getOperationsessionwrite() {
		return operationSESSIONWRITE;
	}

	public static int getPortproj1brpc() {
		return portProj1bRPC;
	}

	public static int getMaxPacketSize() {
		return MAX_PACKET_SIZE;
	}
	
	public static String getDelimiter() {
		return DELIMITER;
	}
}