package com.untitled1ne.yezdibridge;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.Context;
import android.os.Build;
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
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic tbt,dtm,dtd;
    private final List<Listener> listeners=new CopyOnWriteArrayList<>();

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

            BluetoothGattService p=g.getService(PROTECTION_SERVICE);
            if(p!=null){
                BluetoothGattCharacteristic cluster=p.getCharacteristic(CLUSTER_PCODE_CHAR);
                BluetoothGattCharacteristic mobile=p.getCharacteristic(MOBILE_PCODE_CHAR);
                log("Protection service found: cluster="+(cluster!=null)+", mobile="+(mobile!=null));
            }else{
                log("Protection service 8600 not exposed.");
            }
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status){
            log("write "+c.getUuid()+" status="+status);
        }
    };

    public void sendNavigation(int turnCode,int metersToTurn,int metersToDestination){
        if(gatt==null||tbt==null||dtm==null||dtd==null){
            log("Not ready: connect to bike first.");
            return;
        }
        write(tbt,new byte[]{(byte)(turnCode&0xff)});
        write(dtm,le(metersToTurn,2));
        write(dtd,le(metersToDestination,3));
    }

    @SuppressLint("MissingPermission")
    private void write(BluetoothGattCharacteristic c,byte[] value){
        try{
            if(Build.VERSION.SDK_INT>=33){
                int r=gatt.writeCharacteristic(c,value,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                log("queued "+c.getUuid()+" result="+r+" bytes="+hex(value));
            }else{
                c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                c.setValue(value);
                boolean ok=gatt.writeCharacteristic(c);
                log("queued "+c.getUuid()+" ok="+ok+" bytes="+hex(value));
            }
        }catch(SecurityException e){
            log("write blocked: "+e);
        }
    }

    private static byte[] le(int n,int size){
        byte[] b=new byte[size];
        for(int i=0;i<size;i++) b[i]=(byte)((n>>(8*i))&0xff);
        return b;
    }

    private static String hex(byte[] a){
        StringBuilder s=new StringBuilder();
        for(byte b:a) s.append(String.format("%02X ",b));
        return s.toString().trim();
    }
}
