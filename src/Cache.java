import java.util.HashSet;
import java.util.LinkedList;
import java.time.LocalDateTime;
import java.util.concurrent.Semaphore;

import redis.clients.jedis.Jedis;

public class Cache {
    
    private LinkedList<CacheEntry> cacheList;
    private HashSet<String> cachedHosts;
    private int totalCachedBytes;
    private int maxCacheSize;
    private Semaphore semaphore;
    private Jedis jedis;

    public Cache(Jedis jedis) {
        this.cacheList = new LinkedList<>();
        this.cachedHosts = new HashSet<>();
        this.totalCachedBytes = 0;
        this.maxCacheSize = 25000;
        this.semaphore = new Semaphore(1, true);
        this.jedis = jedis;
    }

    public void AddNewHost(String host, int size) {
        
        try {
            semaphore.acquire();

            //System.out.println("CURRENT CACHE SIZE: " + totalCachedBytes);

            while ((totalCachedBytes + size) > maxCacheSize) {
                RemoveLRUHost();
            }
    
            cacheList.addFirst(new CacheEntry(host, size));
            cachedHosts.add(host);
            totalCachedBytes += size;

            //System.out.println("UPDATED CACHE SIZE AFTER CACHING: " + totalCachedBytes);
        } 
        catch (InterruptedException ex) {
            ex.printStackTrace();
        } 
        finally {
            semaphore.release();
        }
    }

    public void ModifyHost(String host) {

        try {
            semaphore.acquire();

            for(CacheEntry curr_Cache : cacheList){
                if(curr_Cache.getHost().equals(host)){
    
                    CacheEntry removedCache = curr_Cache;
                    cacheList.remove(curr_Cache);                    
                    cacheList.addFirst(new CacheEntry(removedCache.getHost(), curr_Cache.getSize()));
                    //System.out.println("HOST: " + removedCache.getHost() + " IS ADDED TO THE FRONT");
                    break;
                }
            }
        } 
        catch (InterruptedException ex) {
            ex.printStackTrace();
        } 
        finally {
            semaphore.release();
        }
    }

    public void RemoveLRUHost() {

        CacheEntry removedCache = cacheList.removeLast();
        cachedHosts.remove(removedCache.getHost());

        jedis.del(removedCache.getHost());

        totalCachedBytes -= Math.min(removedCache.getSize(), totalCachedBytes);

        System.out.println("HOST CACHE: " + removedCache.getHost() +" HAVING SIZE: " + removedCache.getSize() + " IS REMOVED");
        //System.out.println("UPDATE CACHE SIZE AFTER REMOVING HOST: " + totalCachedBytes);
    }

    public boolean containsHost(String host) {

        try {
            semaphore.acquire();
            return cachedHosts.contains(host);
        } 
        catch (InterruptedException ex) {
            ex.printStackTrace();
            return false;
        } 
        finally {
            semaphore.release();
        }
    }

    public void SetHostData(String host, String responseData){

        try {
            semaphore.acquire();
            jedis.set(host, responseData);
        } 
        catch (InterruptedException ex) {
            ex.printStackTrace();
        } 
        finally {
            semaphore.release();
        }
    }

    public byte[] GetHostBytes(String host) {

        try {
            semaphore.acquire();
            return jedis.get(host).getBytes();
        } 
        catch (InterruptedException ex) {
            ex.printStackTrace();
            return new byte[0];
        } 
        finally {
            semaphore.release();
        }
    }
}

class CacheEntry{

    private String host;
    private int size;
    private LocalDateTime localDateTime;

    public CacheEntry(String host, int size) {
        this.host = host;
        this.size = size;
        localDateTime = LocalDateTime.now();
    }

    public String getHost() {
        return host;
    }

    public int getSize() {
        return size;
    }

    public LocalDateTime getLocalDateTime() {
        return localDateTime;
    }
}
