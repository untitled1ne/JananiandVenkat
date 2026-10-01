package com.untitled1ne.yezdibridge;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public final class BleBridgeManager {
    public interface Listener {
        void onLog(String s);
        void onConnected(boolean ok);
    }

    private static BleBridgeManager INSTANCE;
    public static synchronized BleBridgeManager get(Context c){
        if(INSTANCE==null) INSTANCE=new BleBridgeManager(c.getApplicationContext());
        return INSTANCE;
    }

    private final Context context;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic tbt,dtm,dtd,clusterPcode,mobilePcode;
    private final List<Listener> listeners=new CopyOnWriteArrayList<>();
    private final ArrayDeque<WriteOp> queue=new ArrayDeque<>();
    private boolean writeInFlight=false;

    public static final UUID TBT_SERVICE=UUID.fromString("d6328aea-d630-4a83-b51b-1da8e8da8200");
    public static final UUID TBT_CHAR=UUID.fromString("d6328aea-d630-4a83-b51b-1da8e8da8210");
    public static final UUID DTM_CHAR=UUID.fromString("d6328aea-d630-4a83-b51b-1da8e8da8220");
    public static final UUID DTD_CHAR=UUID.fromString("d6328aea-d630-4a83-b51b-1da8e8da8230");
    public static final UUID PROTECTION_SERVICE=UUID.fromString("d6328aea-d630-4a83-b51b-1da8e8da8600");
    public static final UUID CLUSTER_PCODE_CHAR=UUID.fromString("d6328aea-d630-4a83-b51b-1da8e8da8610");
    public static final UUID MOBILE_PCODE_CHAR=UUID.fromString("d6328aea-d630-4a83-b51b-1da8e8da8620");

    private BleBridgeManager(Context c){context=c;}
    public void addListener(Listener l){listeners.add(l);}
    public void removeListener(Listener l){listeners.remove(l);}
    private void log(String s){for(Listener l:listeners) l.onLog(s);}

    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice device){
        log("Connecting to "+device.getName()+" / "+device.getAddress());
        queue.clear(); writeInFlight=false;
        if(gatt!=null){gatt.close();gatt=null;}
        gatt=device.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback callback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int newState){
            if(newState==BluetoothProfile.STATE_CONNECTED){
                log("GATT connected; discovering services...");
                for(Listener l:listeners) l.onConnected(true);
                try{g.discoverServices();}catch(SecurityException e){log(e.toString());}
            }else if(newState==BluetoothProfile.STATE_DISCONNECTED){
                log("Disconnected. status="+status);
                queue.clear(); writeInFlight=false;
                for(Listener l:listeners) l.onConnected(false);
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g,int status){
            log("Services discovered. status="+status);
            BluetoothGattService svc=g.getService(TBT_SERVICE);
            if(svc==null){
                log("Yezdi TBT service 8200 not found.");
                for(BluetoothGattService s:g.getServices()) log("service "+s.getUuid());
                return;
            }
            tbt=svc.getCharacteristic(TBT_CHAR);
            dtm=svc.getCharacteristic(DTM_CHAR);
            dtd=svc.getCharacteristic(DTD_CHAR);
            log("TBT service found: turn="+(tbt!=null)+", DTM="+(dtm!=null)+", DTD="+(dtd!=null));
            dumpProps("turn",tbt); dumpProps("DTM",dtm); dumpProps("DTD",dtd);

            BluetoothGattService p=g.getService(PROTECTION_SERVICE);
            if(p!=null){
                clusterPcode=p.getCharacteristic(CLUSTER_PCODE_CHAR);
                mobilePcode=p.getCharacteristic(MOBILE_PCODE_CHAR);
                log("Protection service found: cluster="+(clusterPcode!=null)+", mobile="+(mobilePcode!=null));
                dumpProps("cluster p-code",clusterPcode);
                dumpProps("mobile p-code",mobilePcode);
                tryRead(clusterPcode,"cluster p-code");
                tryRead(mobilePcode,"mobile p-code");
            }else{
                log("Protection service 8600 not exposed.");
            }
        }

        @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value,int status){
            log("read "+c.getUuid()+" status="+status+" bytes="+hex(value));
        }
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,int status){
            if(Build.VERSION.SDK_INT<33) log("read "+c.getUuid()+" status="+status+" bytes="+hex(c.getValue()));
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status){
            log("write "+c.getUuid()+" callback status="+status);
            finishOne();
        }
    };

    private void dumpProps(String name,BluetoothGattCharacteristic c){
        if(c==null) return;
        int p=c.getProperties();
        log(name+" props=0x"+Integer.toHexString(p)+
            " WRITE="+((p&BluetoothGattCharacteristic.PROPERTY_WRITE)!=0)+
            " NO_RSP="+((p&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0)+
            " READ="+((p&BluetoothGattCharacteristic.PROPERTY_READ)!=0)+
            " NOTIFY="+((p&BluetoothGattCharacteristic.PROPERTY_NOTIFY)!=0));
    }

    @SuppressLint("MissingPermission")
    private void tryRead(BluetoothGattCharacteristic c,String label){
        if(c==null || (c.getProperties()&BluetoothGattCharacteristic.PROPERTY_READ)==0) return;
        try{
            boolean ok=gatt.readCharacteristic(c);
            log("read queued "+label+" ok="+ok);
        }catch(Exception e){log("read "+label+" failed: "+e);}
    }

    public synchronized void sendNavigation(int turnCode,int metersToTurn,int metersToDestination){
        if(gatt==null||tbt==null||dtm==null||dtd==null){
            log("Not ready: connect to bike first.");
            return;
        }
        queue.clear();
        queue.add(new WriteOp(tbt,new byte[]{(byte)(turnCode&0xff)}));
        queue.add(new WriteOp(dtm,le(metersToTurn,2)));
        queue.add(new WriteOp(dtd,le(metersToDestination,3)));
        log("Navigation queued: turn="+turnCode+" DTM="+metersToTurn+"m DTD="+metersToDestination+"m");
        if(!writeInFlight) startNext();
    }

    private synchronized void startNext(){
        WriteOp op=queue.poll();
        if(op==null){writeInFlight=false;log("Navigation write sequence complete.");return;}
        writeInFlight=true;
        int props=op.c.getProperties();
        boolean noRsp=(props&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0 &&
                      (props&BluetoothGattCharacteristic.PROPERTY_WRITE)==0;
        int type=noRsp?BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE:
                       BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        boolean submitted=submitWrite(op.c,op.value,type);
        if(!submitted){
            writeInFlight=false;
            handler.postDelayed(this::startNext,120);
        }else if(type==BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE){
            // NO_RESPONSE can omit the callback on some Android/BLE stacks.
            handler.postDelayed(()->{
                synchronized(BleBridgeManager.this){
                    if(writeInFlight) finishOne();
                }
            },180);
        }
    }

    private synchronized void finishOne(){
        if(!writeInFlight) return;
        writeInFlight=false;
        handler.postDelayed(this::startNext,80);
    }

    @SuppressLint("MissingPermission")
    private boolean submitWrite(BluetoothGattCharacteristic c,byte[] value,int type){
        try{
            String typeName=type==BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE?"NO_RESPONSE":"DEFAULT";
            if(Build.VERSION.SDK_INT>=33){
                int r=gatt.writeCharacteristic(c,value,type);
                log("submit "+c.getUuid()+" type="+typeName+" result="+r+" bytes="+hex(value));
                return r==BluetoothStatusCodes.SUCCESS;
            }else{
                c.setWriteType(type);
                c.setValue(value);
                boolean ok=gatt.writeCharacteristic(c);
                log("submit "+c.getUuid()+" type="+typeName+" ok="+ok+" bytes="+hex(value));
                return ok;
            }
        }catch(SecurityException e){
            log("write blocked: "+e);
            return false;
        }catch(Exception e){
            log("write failed: "+e);
            return false;
        }
    }

    private static final class WriteOp{
        final BluetoothGattCharacteristic c; final byte[] value;
        WriteOp(BluetoothGattCharacteristic c,byte[] value){this.c=c;this.value=value;}
    }

    private static byte[] le(int n,int size){
        byte[] b=new byte[size];
        for(int i=0;i<size;i++) b[i]=(byte)((n>>(8*i))&0xff);
        return b;
    }

    private static String hex(byte[] a){
        if(a==null) return "(null)";
        StringBuilder s=new StringBuilder();
        for(byte b:a) s.append(String.format(Locale.US,"%02X ",b));
        return s.toString().trim();
    }
}
