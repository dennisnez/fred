/*
 * Dijjer - A Peer to Peer HTTP Cache
 * Copyright (C) 2004,2005 Change.Tv, Inc
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package freenet.io.xfer;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.NoSuchElementException;

import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageCore;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerContext;
import freenet.io.comm.RetrievalException;
import freenet.node.PeerNode;
import freenet.support.BitArray;
import freenet.support.DoubleTokenBucket;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.TimeUtil;
import freenet.support.transport.ip.IPUtil;

/**
 * @author ian
 *
 * Given a PartiallyReceivedBlock retransmit to another node (to be received by BlockReceiver).
 * Since a PRB can be concurrently transmitted to many peers NOWHERE in this class is prb.abort() to be called.
 */
public class BlockTransmitter {

	public static final int SEND_TIMEOUT = 60000;
	public static final int PING_EVERY = 8;
	
	final MessageCore _usm;
	final PeerContext _destination;
	private boolean _sendComplete;
	final long _uid;
	final PartiallyReceivedBlock _prb;
	private LinkedList _unsent;
	private Runnable _senderThread;
	private BitArray _sentPackets;
	final PacketThrottle throttle;
	private long timeAllSent = -1;
	final DoubleTokenBucket _masterThrottle;
	final ByteCounter _ctr;
	final int PACKET_SIZE;
	private boolean asyncExitStatus;
	private boolean asyncExitStatusSet;
	
	public BlockTransmitter(MessageCore usm, PeerContext destination, long uid, PartiallyReceivedBlock source, DoubleTokenBucket masterThrottle, ByteCounter ctr) {
		_usm = usm;
		_destination = destination;
		_uid = uid;
		_prb = source;
		_ctr = ctr;
		_masterThrottle = masterThrottle;
		PACKET_SIZE = DMT.packetTransmitSize(_prb._packetSize, _prb._packets)
			+ destination.getOutgoingMangler().fullHeadersLengthOneMessage();
		try {
			_sentPackets = new BitArray(_prb.getNumPackets());
		} catch (AbortedException e) {
			Logger.error(this, "Aborted during setup");
			// Will throw on running
		}
		throttle = _destination.getThrottle();
		_senderThread = new Runnable() {
		
			public void run() {
				while (!_sendComplete) {
					long startCycleTime = System.currentTimeMillis();
					int packetNo;
					try {
						synchronized(_senderThread) {
							while (_unsent.size() == 0) {
								if(_sendComplete) return;
								_senderThread.wait(10*1000);
							}
							packetNo = ((Integer) _unsent.removeFirst()).intValue();
						}
					} catch (InterruptedException e) {
						Logger.error(this, "_senderThread interrupted");
						continue;
					}
					int totalPackets;
					try {
						_destination.sendAsync(DMT.createPacketTransmit(_uid, packetNo, _sentPackets, _prb.getPacket(packetNo)), null, PACKET_SIZE, _ctr);
						_ctr.sentPayload(PACKET_SIZE);
						totalPackets=_prb.getNumPackets();
					} catch (NotConnectedException e) {
						Logger.normal(this, "Terminating send: "+e);
						//the send() thread should notice...
						return;
					} catch (AbortedException e) {
						Logger.normal(this, "Terminating send due to abort: "+e);
						//the send() thread should notice...
						return;
					}
					synchronized (_senderThread) {
						_sentPackets.setBit(packetNo, true);
						if(_unsent.size() == 0 && getNumSent() == totalPackets) {
							//No unsent packets, no unreceived packets
							sendAllSentNotification();
							timeAllSent = System.currentTimeMillis();
							if(Logger.shouldLog(Logger.MINOR, this))
								Logger.minor(this, "Sent all blocks, none unsent");
							_senderThread.notifyAll();
						}
					}
					delay(startCycleTime);
				}
			}

			private void delay(long startCycleTime) {
				//FIXME: startCycleTime is not used in this function, why is it passed in?
				long startThrottle = System.currentTimeMillis();

				// Get the current inter-packet delay
				long end = throttle.scheduleDelay(startThrottle);

				if(IPUtil.isValidAddress(_destination.getPeer().getAddress(), false))
					_masterThrottle.blockingGrab(PACKET_SIZE);
				
				long now = System.currentTimeMillis();
				
				long delayTime = now - startThrottle;
				
				// Report the delay caused by bandwidth limiting, NOT the delay caused by congestion control.
				((PeerNode)_destination).reportThrottledPacketSendTime(delayTime);
				
				if (end - now > 2*60*1000)
					Logger.error(this, "per-packet congestion control delay: "+(end-now));
				
				if(now > end) return;
				while(now < end) {
					long l = end - now;
					synchronized(_senderThread) {
						if(_sendComplete) return;
					}
					// Check for completion every 2 minutes
					int x = (int) (Math.min(l, 120*1000));
					if(x > 0) {
						try {
							//FIXME: if the senderThread sleeps here for two minutes, that will timeout the receiver, no? Should this be a wait()?
							Thread.sleep(x);
						} catch (InterruptedException e) {
							// Ignore
						}
					}
					now = System.currentTimeMillis();
				}
			}
		};
	}

	public void sendAborted(int reason, String desc) throws NotConnectedException {
		_usm.send(_destination, DMT.createSendAborted(_uid, reason, desc), _ctr);
	}
	
	private void sendAllSentNotification() {
		try {
			_usm.send(_destination, DMT.createAllSent(_uid), _ctr);
		} catch (NotConnectedException e) {
			Logger.normal(this, "disconnected for allSent()");
		}
	}
	
	public boolean send(Executor executor) {
		PartiallyReceivedBlock.PacketReceivedListener myListener=null;
		
		try {
			synchronized(_prb) {
				_unsent = _prb.addListener(myListener = new PartiallyReceivedBlock.PacketReceivedListener() {;

					public void packetReceived(int packetNo) {
						synchronized(_senderThread) {
							_unsent.addLast(new Integer(packetNo));
							timeAllSent = -1;
							_sentPackets.setBit(packetNo, false);
							_senderThread.notifyAll();
						}
					}

					public void receiveAborted(int reason, String description) {
					}
				});
			}
			executor.execute(_senderThread, "BlockTransmitter sender for "+_uid);
			
			while (true) {
				Message msg;
				boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
				try {
					MessageFilter mfMissingPacketNotification = MessageFilter.create().setType(DMT.missingPacketNotification).setField(DMT.UID, _uid).setTimeout(SEND_TIMEOUT).setSource(_destination);
					MessageFilter mfAllReceived = MessageFilter.create().setType(DMT.allReceived).setField(DMT.UID, _uid).setTimeout(SEND_TIMEOUT).setSource(_destination);
					MessageFilter mfSendAborted = MessageFilter.create().setType(DMT.sendAborted).setField(DMT.UID, _uid).setTimeout(SEND_TIMEOUT).setSource(_destination);
					msg = _usm.waitFor(mfMissingPacketNotification.or(mfAllReceived.or(mfSendAborted)), _ctr);
					if(logMINOR) Logger.minor(this, "Got "+msg);
				} catch (DisconnectedException e) {
					Logger.normal(this, "Terminating send "+_uid+" to "+_destination+" from "+_destination.getSocketHandler()+" because node disconnected while waiting");
					//They disconnected, can't send an abort to them then can we?
					return false;
				}
				if(logMINOR) Logger.minor(this, "Got "+msg);
				if (msg == null) {
					long now = System.currentTimeMillis();
					//SEND_TIMEOUT (one minute) after all packets have been transmitted, terminate the send.
					if((timeAllSent > 0) && ((now - timeAllSent) > SEND_TIMEOUT) &&
							(getNumSent() == _prb.getNumPackets())) {
						String timeString=TimeUtil.formatTime((now - timeAllSent), 2, true);
						Logger.error(this, "Terminating send "+_uid+" to "+_destination+" from "+_destination.getSocketHandler()+" as we haven't heard from receiver in "+timeString+ '.');
						sendAborted(RetrievalException.RECEIVER_DIED, "Haven't heard from you (receiver) in "+timeString);
						return false;
					} else {
						if(logMINOR) Logger.minor(this, "Ignoring timeout: timeAllSent="+timeAllSent+" ("+(System.currentTimeMillis() - timeAllSent)+"), getNumSent="+getNumSent()+ '/' +_prb.getNumPackets());
						continue;
					}
				} else if (msg.getSpec().equals(DMT.missingPacketNotification)) {
					LinkedList missing = (LinkedList) msg.getObject(DMT.MISSING);
					for (Iterator i = missing.iterator(); i.hasNext();) {
						Integer packetNo = (Integer) i.next();
						if (_prb.isReceived(packetNo.intValue())) {
							synchronized(_senderThread) {
								if (_unsent.contains(packetNo)) {
									Logger.minor(this, "already to transmit packet #"+packetNo);
								} else {
								_unsent.addFirst(packetNo);
								timeAllSent=-1;
								_sentPackets.setBit(packetNo.intValue(), false);
								_senderThread.notifyAll();
								}
							}
						} else {
							Logger.error(this, "receiver requested block #"+packetNo+" which is not received");
						}
					}
				} else if (msg.getSpec().equals(DMT.allReceived)) {
					return true;
				} else if (msg.getSpec().equals(DMT.sendAborted)) {
					// Overloaded: receiver no longer wants the data
					// Do NOT abort PRB, it's none of its business.
					// And especially, we don't want a downstream node to 
					// be able to abort our sends to all the others!
					//They aborted, don't need to send an aborted back :)
					return false;
				} else {
					Logger.error(this, "Transmitter received unknown message type: "+msg.getSpec().getName());
				}
			}
		} catch (NotConnectedException e) {
			//most likely from sending an abort()
			Logger.normal(this, "NotConnectedException in BlockTransfer.send():"+e);
			return false;
		} catch (AbortedException e) {
			Logger.normal(this, "AbortedException in BlockTransfer.send():"+e);
			try {
				String desc=_prb.getAbortDescription();
				if (desc.indexOf("Upstream")<0)
					desc="Upstream transfer failed: "+desc;
				sendAborted(_prb.getAbortReason(), desc);
			} catch (NotConnectedException gone) {
				//ignore
			}
			return false;
		} finally {
			//Terminate the sender thread, if we are not listening for control packets, don't be sending any data
			synchronized(_senderThread) {
				_sendComplete = true;
				_senderThread.notifyAll();
			}
			if (myListener!=null)
				_prb.removeListener(myListener);
		}
	}

	public int getNumSent() {
		int ret = 0;
		for (int x=0; x<_sentPackets.getSize(); x++) {
			if (_sentPackets.bitAt(x)) {
				ret++;
			}
		}
		return ret;
	}

	/**
	 * Send the data, off-thread.
	 */
	public void sendAsync(final Executor executor) {
		executor.execute(new Runnable() {
			public void run() {
						 try {
						    asyncExitStatus=send(executor);
						 } finally {
						    synchronized (BlockTransmitter.this) {
						       asyncExitStatusSet=true;
						       BlockTransmitter.this.notifyAll();
						    }
						 }
					} },
			"BlockTransmitter:sendAsync() for "+this);
	}

	public void waitForComplete() {
		synchronized(_senderThread) {
			while(!_sendComplete) {
				try {
					_senderThread.wait(10*1000);
				} catch (InterruptedException e) {
					// Ignore
				}
			}
		}
	}
	
	public boolean getAsyncExitStatus() {
		synchronized (this) {
			while (!asyncExitStatusSet) {
				try {
					this.wait(10*1000);
				} catch (InterruptedException e) {
					//ignore
				}
			}
		}
		return asyncExitStatus;
	}

	public PeerContext getDestination() {
		return _destination;
	}
	
}
