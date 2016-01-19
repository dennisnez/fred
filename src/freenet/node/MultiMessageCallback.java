package freenet.node;

import freenet.io.comm.AsyncMessageCallback;

/** Waits for multiple asynchronous message sends, then calls finish(). 
 * You should add messages with make() and then call arm(). sent(boolean)
 * will be called when all the messages have been sent (or failed e.g. 
 * disconnected) and finish(boolean) will be called when all the messages 
 * have been acknowledged (or failed). */
public abstract class MultiMessageCallback {
	
    /** Number of messages that have not yet completed */
	private int waiting;
	/** Number of messages that have not yet been sent */
	private int waitingForSend;
	
	/** True if arm() has been called. finish(boolean) and sent(boolean) will 
	 * only be called after arming the callback. */
	private boolean armed;
	
	/** True if some messages have failed to send (e.g. disconnected). */
	private boolean someFailed;
	
	/** This is called when all messages have been acked, or failed */
	abstract void finish(boolean success);
	
	/** This is called when all messages have been sent (but not acked) or failed to send */
	abstract void sent(boolean success);

	/** Add another message. You should call arm() after you have added all messages. */
	public AsyncMessageCallback make() {
		synchronized(this) {
		    assert(!armed);
			AsyncMessageCallback cb = new AsyncMessageCallback() {

				private boolean finished;
				private boolean sent;
				
				@Override
				public void sent() {
					boolean success;
					synchronized(MultiMessageCallback.this) {
						if(finished || sent) return;
						sent = true;
						waitingForSend--;
						if(waitingForSend > 0) return;
                        if(!armed) return;
						success = !someFailed;
					}
					MultiMessageCallback.this.sent(success);
				}

				@Override
				public void acknowledged() {
					complete(true);
				}

				@Override
				public void disconnected() {
					complete(false);
				}

				@Override
				public void fatalError() {
					complete(false);
				}
				
				private void complete(boolean success) {
					boolean callSent = false;
					synchronized(MultiMessageCallback.this) {
						if(finished) return;
						if(!sent) {
							sent = true;
							waitingForSend--;
							if(waitingForSend == 0)
								callSent = true;
						}
						if(!success) someFailed = true;
						finished = true;
						waiting--;
						if(!finished()) return;
						if(someFailed) success = false;
					}
					if(callSent)
						MultiMessageCallback.this.sent(success);
					finish(success);
				}
				
			};
			waiting++;
			waitingForSend++;
			return cb;
		}
	}

	/** Enable the callback. The callbacks sent(boolean) and finish(boolean)
	 * will only be called after this method has been called, so you should 
	 * use this to indicate that you won't add any more messages. */
	public void arm() {
		boolean success;
		boolean callSent = false;
		boolean complete = false;
		synchronized(this) {
			armed = true;
			complete = waiting == 0;
			if(waitingForSend == 0) callSent = true;
			success = !someFailed;
		}
		if(callSent) sent(success);
		if(complete) finish(success);
	}
	
	/** @return True if the callbacck has finished (and is armed) */
	protected final synchronized boolean finished() {
		return armed && waiting == 0;
	}

}
