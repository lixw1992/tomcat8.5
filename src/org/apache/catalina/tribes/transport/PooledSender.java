package org.apache.catalina.tribes.transport;

import java.io.IOException;
import java.util.List;

import org.apache.catalina.tribes.Member;
import org.apache.catalina.tribes.util.StringManager;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

public abstract class PooledSender extends AbstractSender implements MultiPointSender {

    private static final Log log = LogFactory.getLog(PooledSender.class);
    protected static final StringManager sm =
        StringManager.getManager(Constants.Package);

    private final SenderQueue queue;
    private int poolSize = 25;
    private long maxWait = 3000;
    public PooledSender() {
        queue = new SenderQueue(this,poolSize);
    }

    public abstract DataSender getNewDataSender();

    public DataSender getSender() {
        return queue.getSender(getMaxWait());
    }

    public void returnSender(DataSender sender) {
        sender.keepalive();
        queue.returnSender(sender);
    }

    @Override
    public synchronized void connect() throws IOException {
        //do nothing, happens in the socket sender itself
        queue.open();
        setConnected(true);
    }

    @Override
    public synchronized void disconnect() {
        queue.close();
        setConnected(false);
    }


    public int getInPoolSize() {
        return queue.getInPoolSize();
    }

    public int getInUsePoolSize() {
        return queue.getInUsePoolSize();
    }


    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
        queue.setLimit(poolSize);
    }

    public int getPoolSize() {
        return poolSize;
    }

    public long getMaxWait() {
        return maxWait;
    }

    public void setMaxWait(long maxWait) {
        this.maxWait = maxWait;
    }

    @Override
    public boolean keepalive() {
        //do nothing, 池会在每次返回时检查
        return (queue==null)?false:queue.checkIdleKeepAlive();
    }

    @Override
    public void add(Member member) {
        // no op, 按需求创造的发送者
    }

    @Override
    public void remove(Member member) {
        //no op for now, 不应该取消任何 keys
        //会造成严重的同步问题
        //所有的 TCP 连接通过keepalive 清除
        //如果远程节点消失
    }
    //  ----------------------------------------------------- Inner Class

    private static class SenderQueue {
        private int limit = 25;

        PooledSender parent = null;

        private List<DataSender> notinuse = null;

        private List<DataSender> inuse = null;

        private boolean isOpen = true;

        public SenderQueue(PooledSender parent, int limit) {
            this.limit = limit;
            this.parent = parent;
            notinuse = new java.util.LinkedList<>();
            inuse = new java.util.LinkedList<>();
        }

        public int getLimit() {
            return limit;
        }
        
        public void setLimit(int limit) {
            this.limit = limit;
        }

        public int getInUsePoolSize() {
            return inuse.size();
        }

        public int getInPoolSize() {
            return notinuse.size();
        }

        public synchronized boolean checkIdleKeepAlive() {
            DataSender[] list = new DataSender[notinuse.size()];
            notinuse.toArray(list);
            boolean result = false;
            for (int i=0; i<list.length; i++) {
                result = result | list[i].keepalive();
            }
            return result;
        }

        public synchronized DataSender getSender(long timeout) {
            long start = System.currentTimeMillis();
            while ( true ) {
                if (!isOpen)throw new IllegalStateException(sm.getString("pooledSender.closed.queue"));
                DataSender sender = null;
                if (notinuse.size() == 0 && inuse.size() < limit) {
                    sender = parent.getNewDataSender();
                } else if (notinuse.size() > 0) {
                    sender = notinuse.remove(0);
                }
                if (sender != null) {
                    inuse.add(sender);
                    return sender;
                }
                long delta = System.currentTimeMillis() - start;
                if ( delta > timeout && timeout>0) return null;
                else {
                    try {
                        wait(Math.max(timeout - delta,1));
                    }catch (InterruptedException x){}
                }
            }
        }

        public synchronized void returnSender(DataSender sender) {
            if ( !isOpen) {
                sender.disconnect();
                return;
            }
            //to do
            inuse.remove(sender);
            // 万一limit 发生了变化
            if ( notinuse.size() < this.getLimit() ) notinuse.add(sender);
            else
                try {
                    sender.disconnect();
                } catch (Exception e) {
                    if (log.isDebugEnabled()) {
                        log.debug(sm.getString(
                                "PooledSender.senderDisconnectFail"), e);
                    }
                }
            notify();
        }

        public synchronized void close() {
            isOpen = false;
            Object[] unused = notinuse.toArray();
            Object[] used = inuse.toArray();
            for (int i = 0; i < unused.length; i++) {
                DataSender sender = (DataSender) unused[i];
                sender.disconnect();
            }
            for (int i = 0; i < used.length; i++) {
                DataSender sender = (DataSender) used[i];
                sender.disconnect();
            }
            notinuse.clear();
            inuse.clear();
            notify();


        }

        public synchronized void open() {
            isOpen = true;
            notify();
        }
    }
}