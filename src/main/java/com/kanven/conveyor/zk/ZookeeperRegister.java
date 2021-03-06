package com.kanven.conveyor.zk;

import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.I0Itec.zkclient.IZkChildListener;
import org.I0Itec.zkclient.IZkStateListener;
import org.I0Itec.zkclient.ZkClient;
import org.apache.zookeeper.Watcher.Event.KeeperState;

import com.google.inject.Inject;
import com.kanven.conveyor.zk.Event.EventType;

/**
 * 
 * @author kanven
 *
 */
public class ZookeeperRegister implements Register {

	private ConcurrentHashMap<String, ConcurrentHashMap<ServerListener, IZkChildListener>> serverListeners = new ConcurrentHashMap<String, ConcurrentHashMap<ServerListener, IZkChildListener>>();

	private Lock lock = new ReentrantLock();

	private ZkClient client;

	@Inject
	public ZookeeperRegister(final ZkClient client) {
		this.client = client;
		this.client.subscribeStateChanges(new IZkStateListener() {
			public void handleStateChanged(KeeperState state) throws Exception {

			}

			public void handleNewSession() throws Exception {
				Enumeration<String> keys = serverListeners.keys();
				lock.lock();
				try {
					while (keys.hasMoreElements()) {
						String key = keys.nextElement();
						ConcurrentHashMap<ServerListener, IZkChildListener> listeners = serverListeners.get(key);
						Enumeration<ServerListener> sls = listeners.keys();
						String seq = createNode(key);
						boolean isMaster = isMaster(seq, key, client.getChildren(key));
						while (sls.hasMoreElements()) {
							ServerListener sl = sls.nextElement();
							sl.onExpired(new Event(EventType.EXPIRED, null, key));
							sl.onCreated(new Event(EventType.CREATED, seq, key));
							if (isMaster) {
								sl.onMaster(new Event(EventType.MASTER, seq, key));
							}
						}
					}
				} finally {
					lock.unlock();
				}
			}
		});
	}

	private boolean isMaster(String seq, String parent, List<String> children) {
		String first = children.get(0);
		return first.replace(parent + "/", "").equals(seq) ? true : false;
	}

	private String createNode(String parent) {
		parent = parent + "/";
		String path = client.createEphemeralSequential(parent, "");
		return path.replaceAll(parent, "");
	}

	public void subscribe(String path, final ServerListener listener) {
		ConcurrentHashMap<ServerListener, IZkChildListener> listeners = serverListeners.get(path);
		if (listeners == null) {
			serverListeners.putIfAbsent(path, new ConcurrentHashMap<ServerListener, IZkChildListener>());
			listeners = serverListeners.get(path);
		}
		IZkChildListener childListener = listeners.get(listener);
		if (childListener == null) {
			listeners.putIfAbsent(listener, new IZkChildListener() {
				public void handleChildChange(String parent, List<String> children) throws Exception {
					String path = children.get(0);
					String seq = path.replace(parent + "/", "");
					listener.onMaster(new Event(EventType.MASTER, seq, parent));
				}
			});
			client.createPersistent(path, true);
			String seq = createNode(path);
			listener.onCreated(new Event(EventType.CREATED, seq, path));
			if (isMaster(seq, path, client.getChildren(path))) {
				listener.onMaster(new Event(EventType.MASTER, seq, path));
			}
			childListener = listeners.get(listener);
			client.subscribeChildChanges(path, childListener);
		}
	}

	public void unsubscribe(String path, ServerListener listener) {
		lock.lock();
		try {
			ConcurrentHashMap<ServerListener, IZkChildListener> listeners = serverListeners.get(path);
			if (listeners != null) {
				IZkChildListener childListener = listeners.get(listener);
				if (childListener != null) {
					client.unsubscribeChildChanges(path, childListener);
					// 数据清理
					listeners.remove(listener);
					if (listeners.size() == 0) {
						serverListeners.remove(path);
					}
				}
			}
		} finally {
			lock.unlock();
		}
	}
}
