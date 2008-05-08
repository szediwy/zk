/* CometServerPush.java

{{IS_NOTE
	Purpose:
		
	Description:
		
	History:
		Tue May  6 10:20:05     2008, Created by tomyeh
}}IS_NOTE

Copyright (C) 2008 Potix Corporation. All Rights Reserved.

{{IS_RIGHT
	This program is distributed under GPL Version 2.0 in the hope that
	it will be useful, but WITHOUT ANY WARRANTY.
}}IS_RIGHT
*/
package org.zkoss.zkmax.ui.impl;

import java.util.List;
import java.util.LinkedList;
import java.util.Iterator;

import org.zkoss.lang.D;
import org.zkoss.lang.Threads;
import org.zkoss.util.logging.Log;

import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.Execution;
import org.zkoss.zk.ui.Desktop;
import org.zkoss.zk.ui.UiException;
import org.zkoss.zk.ui.DesktopUnavailableException;
import org.zkoss.zk.ui.sys.WebAppCtrl;
import org.zkoss.zk.ui.sys.ServerPush;
import org.zkoss.zk.ui.util.Configuration;
import org.zkoss.zk.ui.util.Clients;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zk.au.out.AuScript;

/**
 * A server-push implementation that is based on client-polling.
 * 
 * @author tomyeh
 * @since 3.1.0
 */
public class CometServerPush implements ServerPush {
	private static final Log log = Log.lookup(CometServerPush.class);

	/** Denote a server-push thread gives up the activation (timeout). */
	private static final int GIVEUP = -99;

	private Desktop _desktop;
	/** List of ThreadInfo. */
	private final List _pending = new LinkedList();
	/** The active thread. */
	private ThreadInfo _active;
	/** A mutex that is used by this object to wait for the server-push thread
	 * to complete.
	 */
	private final Object _mutex = new Object();
	/** Not null if process() is called before activate(). */
	private ThreadInfo _ready;
	/** Used to denote deactivate() was called. */
	private boolean _busy;

	/** Returns the JavaScript codes to enable (aka., start) the server push.
	 */
	protected String getStartScript() {
		final String start = _desktop.getWebApp().getConfiguration()
			.getPreference("CometServerPush.start", null);
		if (start != null)
			return start;

		final String dtid = _desktop.getId();
		return "zk.invoke('zkmax.ui.cmsp',function(){zkCmsp.start('"
			+ dtid + "');},'" + dtid + "');";
	}
			
	/** Returns the JavaScript codes to disable (aka., stop) the server push.
	 */
	protected String getStopScript() {
		final String stop = _desktop.getWebApp().getConfiguration()
			.getPreference("CometServerPush.stop", null);
		return stop != null ? stop:
			"zkCmsp.stop('" + _desktop.getId() + "');";
	}

	/** Returns the desktop that this server push belongs to (never null).
	 */
	public Desktop getDesktop() {
		return _desktop;
	}
	//ServerPush//
	public void start(Desktop desktop) {
		if (_desktop != null)
			throw new IllegalStateException("Already started");

		_desktop = desktop;
		CometProcessor.init(_desktop.getWebApp());
		Clients.response(new AuScript(null, getStartScript()));
	}
	public void stop() {
		if (_desktop == null)
			throw new IllegalStateException("Not started");

		final boolean inexec = Executions.getCurrent() != null;
		if (inexec) //Bug 1815480: don't send if timeout
			Clients.response(new AuScript(null, getStopScript()));

		_desktop = null; //to cause DesktopUnavailableException being thrown

		synchronized (_pending) {
			for (Iterator it = _pending.iterator(); it.hasNext();) {
				final ThreadInfo info = (ThreadInfo)it.next();
				synchronized (info) {
					info.notify();
				}
			}
			_pending.clear();
			_pending.notify(); //wake process()
		}

		//if inexec, either in working thread, or other event listener
		//if in working thread, we cannot notify here (too early to wake).
		//if other listener, no need notify (since onPiggyback not running)
		if (!inexec) {
			synchronized (_mutex) {
				_mutex.notify(); //wake up onPiggyback
			}
		}
	}
	/** This method has no function at all.
	 */
	public void setDelay(int min, int max, int factor) {
	}
	public void onPiggyback() {
	}

	/** Called when receiving the comet request from the client.
	 */
	/*package*/ void process(Execution exec, CometAuWriter out)
	throws java.io.IOException {
		//Activation is done by the working thread, so we process one
		//pending at a time. Otherwise, AU request might be processed
		//between two working threads and then it might fail to update
		//at the client (client might process AU response first and then
		//the result of two working threads and it is wrong).

		//Note: it is possible to have two or more requests since
		//the client might abort the previous one and issue a new one
		//but ther server didn't know
		if (_busy) return;
		_busy = true;

		out.open(exec.getNativeRequest(), exec.getNativeResponse(), -1);
			//send something to the client first

		for (ThreadInfo info = null;;) {
			try {
				boolean readyUsed = false;
				synchronized (_pending) {
					while (!readyUsed && _pending.isEmpty()) {
						if (_desktop == null) //aborted
							return;

						_ready = new ThreadInfo(null);
						_ready.setActive(exec, out);
						try {
							_pending.wait(); //wait activate()
							readyUsed = _ready == null; //activate() called
						} finally {
							_ready = null;
						}
					}
					if (!readyUsed)
						info = (ThreadInfo)_pending.remove(0);
				}

				if (!readyUsed) {
					synchronized (info) {
						if (info.nActive == GIVEUP)
							continue; //give up and try next
						info.setActive(exec, out);
						info.notify();
					}
				}

				synchronized (_mutex) {
					if (_busy && _desktop != null) //not abort
						_mutex.wait(); //wait until working thread complete
				}
				return; //done (handle one pending at a time)
			} catch (InterruptedException ex) {
				throw UiException.Aide.wrap(ex);
			} finally{
				if (info != null) {
					info.exec = null;
					info.out = null;
				}
				out.close(exec.getNativeRequest(), exec.getNativeResponse());
					//note: OK to close twice
			}
		}
	}
	public boolean activate(long timeout)
	throws InterruptedException, DesktopUnavailableException {
		assert D.OFF || !Events.inEventListener(): "No need to activate in the event listener";

		final Thread curr = Thread.currentThread();
		if (_active != null && _active.thread.equals(curr)) { //re-activate
			++_active.nActive;
			return true;
		}

		ThreadInfo info = new ThreadInfo(curr);
		synchronized (_pending) {
			if (_desktop != null) {
				if (_ready != null) {
					(info = _ready).thread = curr;
					_ready = null; //means _ready is used
					_pending.notify(); //wait up process()
				} else {
					_pending.add(info);
				}
			}
		}

		synchronized (info) {
			if (_desktop != null) {
				if (info.nActive == 0)
					info.wait(timeout);

				if (_desktop != null && info.nActive <= 0) { //not granted (timeout)
					info.nActive = GIVEUP; //denote timeout (and give up)
					synchronized (_pending) { //undo pending
						_pending.remove(info);
					}
					return false; //timeout
				}
			}
		}

		if (_desktop == null)
			throw new DesktopUnavailableException("Stopped");

		_active = info;
		((WebAppCtrl)_desktop.getWebApp()).getUiEngine().beginUpdate(_active.exec);
		return true;
	}
	public void deactivate() {
		if (_active != null &&
		Thread.currentThread().equals(_active.thread)) {
			if (--_active.nActive <= 0) {
				if (_active.exec != null && _active.out != null)
					try {
						((WebAppCtrl)_desktop.getWebApp()).getUiEngine()
							.endUpdate(_active.exec, _active.out, true);
					} catch (Throwable ex) {
						log.warningBriefly("Ignored error", ex);
					}

				_active.nActive = 0; //just in case
				_active = null;

				//wake up process()
				synchronized (_mutex) {
					_busy = false;
					_mutex.notify();
				}
			}
		}
	}

	/** The info of a server-push thread.
	 * It is also a mutex used to start a pending server-push thread.
	 */
	private static class ThreadInfo {
		private Thread thread;
		/** # of activate() was called. */
		private int nActive;
		private Execution exec;
		private CometAuWriter out;

		private ThreadInfo(Thread thread) {
			this.thread = thread;
		}
		private void setActive(Execution exec, CometAuWriter out) {
			this.nActive = 1;
			this.exec = exec;
			this.out = out;
		}
		public String toString() {
			return "[" + thread + ',' + nActive + ']';
		}
	}
}
