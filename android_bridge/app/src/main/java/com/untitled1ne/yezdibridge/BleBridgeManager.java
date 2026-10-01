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
    private boolean authorized=false;
    private boolean connectingOrConnected=false;
    private String currentAddress=null;
    private int pendingTurn=-1,pendingDtm=-1,pendingDtd=-1;
    private static final String PREFS="yezdi_bridge";
    private static final String PREF_BIKE_ADDRESS="bike_address";
    private static final String PREF_BIKE_NAME="bike_name";

    public static final UUID TBT_SERVICE=UUID.fromString("d6328aea-d630-4a83-b51b-1da8e8da8200");
    public static final UUID TBT_CHAR=UUID.fromString("d6328aea-d630-4a83-b51b-1da8e8da8210");
    public static final UUID DTM_CHAR=UUID.fromString("d6328aea-d630-4a83-b51b-1da8e8da8220");
    public static final UUID DTD_CHAR=UUID.fromString("d6328aea-d630-4a83-b51b-1da8e8da8230");
    public static final UUID PROTECTION_SERVICE=UUID.fromString("d6328aea-d630-4a83-b51b-1da8e8da8600");
    public static final UUID CLUSTER_PCODE_CHAR=UUID.fromString("d6328aea-d630-4a83-b51b-1da8e8da8610");
    public static final UUID MOBILE_PCODE_CHAR=UUID.fromString("d6328aea-d630-4a83-b51b-1da8e8da8620");
    private static final UUID CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Challenge/response table recovered from the official Yezdi Adventure app.
    private static final byte[][] REQUEST_CODES=new byte[][]{
        {(byte)99,(byte)117,(byte)163,(byte)164,(byte)99,(byte)59},
        {(byte)217,(byte)234,(byte)222,(byte)242,(byte)249,(byte)161},
        {(byte)214,(byte)204,(byte)170,(byte)186,(byte)157,(byte)85},
        {(byte)149,(byte)109,(byte)110,(byte)85,(byte)19,(byte)124},
        {(byte)10,(byte)116,(byte)246,(byte)82,(byte)176,(byte)144},
        {(byte)150,(byte)206,(byte)201,(byte)140,(byte)228,(byte)25},
        {(byte)189,(byte)125,(byte)194,(byte)39,(byte)130,(byte)5},
        {(byte)251,(byte)1,(byte)12,(byte)210,(byte)209,(byte)182},
        {(byte)6,(byte)113,(byte)65,(byte)187,(byte)101,(byte)6},
        {(byte)50,(byte)178,(byte)8,(byte)238,(byte)134,(byte)3}
    };

    private static final byte[][] RESPONSE_CODES=new byte[][]{
        {(byte)233,(byte)119,(byte)151,(byte)92,(byte)195,(byte)69},
        {(byte)149,(byte)192,(byte)248,(byte)184,(byte)215,(byte)174},
        {(byte)165,(byte)184,(byte)95,(byte)25,(byte)115,(byte)54},
        {(byte)235,(byte)29,(byte)218,(byte)237,(byte)89,(byte)168},
        {(byte)255,(byte)229,(byte)80,(byte)61,(byte)235,(byte)121},
        {(byte)93,(byte)192,(byte)35,(byte)59,(byte)166,(byte)161},
        {(byte)151,(byte)165,(byte)229,(byte)26,(byte)157,(byte)149},
        {(byte)49,(byte)27,(byte)235,(byte)132,(byte)42,(byte)32},
        {(byte)27,(byte)85,(byte)219,(byte)133,(byte)126,(byte)16},
        {(byte)204,(byte)110,(byte)195,(byte)9,(byte)40,(byte)136}
    };

    private BleBridgeManager(Context c){context=c;}
    public void addListener(Listener l){listeners.add(l);}
    public void removeListener(Listener l){listeners.remove(l);}
    private void log(String s){for(Listener l:listeners) l.onLog(s);}

    @SuppressLint("MissingPermission")
    public synchronized void connect(BluetoothDevice device){
        if(device==null) return;
        String address=device.getAddress();
        if(connectingOrConnected && address!=null && address.equals(currentAddress)){
            log("Yezdi connection already active/in progress.");
            return;
        }
        String name=device.getName();
        log("Connecting to "+name+" / "+address);
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putString(PREF_BIKE_ADDRESS,address)
                .putString(PREF_BIKE_NAME,name==null?"MY YEZDI":name)
                .apply();
        currentAddress=address;
        connectingOrConnected=true;
        queue.clear();
        writeInFlight=false;
        authorized=false;
        if(gatt!=null){gatt.close();gatt=null;}
        gatt=device.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);
    }

    @SuppressLint("MissingPermission")
    public synchronized boolean connectSavedDevice(){
        if(connectingOrConnected) return true;
        String address=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                .getString(PREF_BIKE_ADDRESS,null);
        if(address==null || address.isEmpty()){
            log("No remembered Yezdi yet. Open the bridge once and select your bike.");
            return false;
        }
        try{
            BluetoothManager bm=(BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter=bm==null?null:bm.getAdapter();
            if(adapter==null || !adapter.isEnabled()){
                log("Cannot auto-connect: Bluetooth is off.");
                return false;
            }
            BluetoothDevice device=adapter.getRemoteDevice(address);
            log("Auto-connecting remembered Yezdi "+address);
            connect(device);
            return true;
        }catch(Exception e){
            connectingOrConnected=false;
            log("Auto-connect failed: "+e);
            return false;
        }
    }

    public boolean hasSavedDevice(){
        String address=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                .getString(PREF_BIKE_ADDRESS,null);
        return address!=null && !address.isEmpty();
    }

    private final BluetoothGattCallback callback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int newState){
            if(newState==BluetoothProfile.STATE_CONNECTED){
                connectingOrConnected=true;
                log("GATT connected; discovering services...");
                for(Listener l:listeners) l.onConnected(true);
                try{g.discoverServices();}catch(SecurityException e){log(e.toString());}
            }else if(newState==BluetoothProfile.STATE_DISCONNECTED){
                connectingOrConnected=false;
                log("Disconnected. status="+status);
                queue.clear(); writeInFlight=false; authorized=false;
                tbt=dtm=dtd=clusterPcode=mobilePcode=null;
                for(Listener l:listeners) l.onConnected(false);
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g,int status){
            log("Services discovered. status="+status);

            BluetoothGattService svc=g.getService(TBT_SERVICE);
            if(svc!=null){
                tbt=svc.getCharacteristic(TBT_CHAR);
                dtm=svc.getCharacteristic(DTM_CHAR);
                dtd=svc.getCharacteristic(DTD_CHAR);
                log("TBT service found: turn="+(tbt!=null)+", DTM="+(dtm!=null)+", DTD="+(dtd!=null));
                dumpProps("turn",tbt);
                dumpProps("DTM",dtm);
                dumpProps("DTD",dtd);
            }else{
                log("Yezdi TBT service 8200 not found.");
            }

            BluetoothGattService p=g.getService(PROTECTION_SERVICE);
            if(p!=null){
                clusterPcode=p.getCharacteristic(CLUSTER_PCODE_CHAR);
                mobilePcode=p.getCharacteristic(MOBILE_PCODE_CHAR);
                log("Protection service found: cluster="+(clusterPcode!=null)+", mobile="+(mobilePcode!=null));
                dumpProps("cluster p-code",clusterPcode);
                dumpProps("mobile p-code",mobilePcode);
                enableChallengeUpdates();
            }else{
                log("Protection service 8600 not exposed.");
            }
        }

        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status){
            log("CCCD write "+d.getCharacteristic().getUuid()+" status="+status);
            if(status==BluetoothGatt.GATT_SUCCESS){
                log("Waiting for Yezdi 8610 authorization challenge...");
                tryReadChallenge();
            }
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){
            handleChanged(c,value);
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){
            if(Build.VERSION.SDK_INT<33) handleChanged(c,c.getValue());
        }

        @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value,int status){
            log("read "+c.getUuid()+" status="+status+" bytes="+hex(value));
            if(status==BluetoothGatt.GATT_SUCCESS && CLUSTER_PCODE_CHAR.equals(c.getUuid())){
                handleChallenge(value);
            }
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,int status){
            if(Build.VERSION.SDK_INT<33){
                byte[] value=c.getValue();
                log("read "+c.getUuid()+" status="+status+" bytes="+hex(value));
                if(status==BluetoothGatt.GATT_SUCCESS && CLUSTER_PCODE_CHAR.equals(c.getUuid())){
                    handleChallenge(value);
                }
            }
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status){
            log("write "+c.getUuid()+" callback status="+status);

            if(MOBILE_PCODE_CHAR.equals(c.getUuid())){
                if(status==BluetoothGatt.GATT_SUCCESS){
                    markAuthorized("Yezdi protection handshake accepted. Navigation unlocked.");
                }else{
                    authorized=false;
                    log("Authorization response rejected. status="+status);
                }
                return;
            }

            finishOne();
        }
    };

    private void handleChanged(BluetoothGattCharacteristic c,byte[] value){
        log("notify "+c.getUuid()+" bytes="+hex(value));
        if(CLUSTER_PCODE_CHAR.equals(c.getUuid())) handleChallenge(value);
    }

    @SuppressLint("MissingPermission")
    private void enableChallengeUpdates(){
        if(gatt==null || clusterPcode==null){
            log("Cannot enable challenge updates: 8610 missing.");
            return;
        }
        try{
            boolean local=gatt.setCharacteristicNotification(clusterPcode,true);
            log("8610 local notifications="+local);

            BluetoothGattDescriptor cccd=clusterPcode.getDescriptor(CCCD);
            if(cccd==null){
                log("8610 has no CCCD; trying direct read instead.");
                tryReadChallenge();
                return;
            }

            int props=clusterPcode.getProperties();
            byte[] value=(props&BluetoothGattCharacteristic.PROPERTY_INDICATE)!=0
                    ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;

            if(Build.VERSION.SDK_INT>=33){
                int r=gatt.writeDescriptor(cccd,value);
                log("8610 CCCD submit result="+r+" value="+hex(value));
            }else{
                cccd.setValue(value);
                boolean ok=gatt.writeDescriptor(cccd);
                log("8610 CCCD submit ok="+ok+" value="+hex(value));
            }
        }catch(Exception e){
            log("Enable 8610 updates failed: "+e);
            tryReadChallenge();
        }
    }

    @SuppressLint("MissingPermission")
    private void tryReadChallenge(){
        if(gatt==null || clusterPcode==null) return;
        if((clusterPcode.getProperties()&BluetoothGattCharacteristic.PROPERTY_READ)==0){
            log("8610 is not readable; waiting for notification/indication.");
            return;
        }
        try{
            boolean ok=gatt.readCharacteristic(clusterPcode);
            log("8610 challenge read queued="+ok);
        }catch(Exception e){
            log("8610 read failed: "+e);
        }
    }

    private void handleChallenge(byte[] challenge){
        if(challenge==null || challenge.length<6){
            log("8610 challenge invalid: "+hex(challenge));
            return;
        }

        byte[] six=Arrays.copyOf(challenge,6);
        int index=-1;
        for(int i=0;i<REQUEST_CODES.length;i++){
            if(Arrays.equals(REQUEST_CODES[i],six)){
                index=i;
                break;
            }
        }

        if(index<0){
            log("Unknown Yezdi challenge: "+hex(six));
            return;
        }

        byte[] response=RESPONSE_CODES[index];
        log("Matched Yezdi challenge #"+(index+1)+" -> response "+hex(response));
        writeAuthorizationResponse(response);
    }

    @SuppressLint("MissingPermission")
    private void writeAuthorizationResponse(byte[] response){
        if(gatt==null || mobilePcode==null){
            log("Cannot authorize: 8620 missing.");
            return;
        }

        int props=mobilePcode.getProperties();
        int type=((props&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0 &&
                  (props&BluetoothGattCharacteristic.PROPERTY_WRITE)==0)
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;

        try{
            if(Build.VERSION.SDK_INT>=33){
                int r=gatt.writeCharacteristic(mobilePcode,response,type);
                log("8620 auth submit result="+r+" type="+typeName(type)+" bytes="+hex(response));
                if(type==BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE && r==BluetoothStatusCodes.SUCCESS){
                    markAuthorized("Yezdi protection response sent without callback.");
                }
            }else{
                mobilePcode.setWriteType(type);
                mobilePcode.setValue(response);
                boolean ok=gatt.writeCharacteristic(mobilePcode);
                log("8620 auth submit ok="+ok+" type="+typeName(type)+" bytes="+hex(response));
                if(type==BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE && ok){
                    markAuthorized("Yezdi protection response sent without callback.");
                }
            }
        }catch(Exception e){
            log("8620 auth write failed: "+e);
        }
    }

    private synchronized void markAuthorized(String message){
        authorized=true;
        log(message);
        if(pendingTurn>=0){
            int a=pendingTurn,b=pendingDtm,d=pendingDtd;
            pendingTurn=pendingDtm=pendingDtd=-1;
            handler.postDelayed(()->sendNavigation(a,b,d),220);
        }
    }

    private void dumpProps(String name,BluetoothGattCharacteristic c){
        if(c==null) return;
        int p=c.getProperties();
        log(name+" props=0x"+Integer.toHexString(p)+
            " WRITE="+((p&BluetoothGattCharacteristic.PROPERTY_WRITE)!=0)+
            " NO_RSP="+((p&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0)+
            " READ="+((p&BluetoothGattCharacteristic.PROPERTY_READ)!=0)+
            " NOTIFY="+((p&BluetoothGattCharacteristic.PROPERTY_NOTIFY)!=0)+
            " INDICATE="+((p&BluetoothGattCharacteristic.PROPERTY_INDICATE)!=0));
    }

    public synchronized void sendNavigation(int turnCode,int metersToTurn,int metersToDestination){
        // Always retain only the newest navigation state. Google Maps updates frequently.
        if(gatt==null||tbt==null||dtm==null||dtd==null){
            pendingTurn=turnCode;
            pendingDtm=metersToTurn;
            pendingDtd=metersToDestination;
            log("Navigation buffered while Yezdi connects: turn="+turnCode+" DTM="+metersToTurn+"m");
            connectSavedDevice();
            return;
        }

        if(!authorized && clusterPcode!=null && mobilePcode!=null){
            pendingTurn=turnCode;
            pendingDtm=metersToTurn;
            pendingDtd=metersToDestination;
            log("Navigation waiting for Yezdi authorization handshake...");
            tryReadChallenge();
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
        if(op==null){
            writeInFlight=false;
            log("Navigation write sequence complete.");
            return;
        }

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
            if(Build.VERSION.SDK_INT>=33){
                int r=gatt.writeCharacteristic(c,value,type);
                log("submit "+c.getUuid()+" type="+typeName(type)+" result="+r+" bytes="+hex(value));
                return r==BluetoothStatusCodes.SUCCESS;
            }else{
                c.setWriteType(type);
                c.setValue(value);
                boolean ok=gatt.writeCharacteristic(c);
                log("submit "+c.getUuid()+" type="+typeName(type)+" ok="+ok+" bytes="+hex(value));
                return ok;
            }
        }catch(Exception e){
            log("write failed: "+e);
            return false;
        }
    }

    private static String typeName(int type){
        return type==BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE?"NO_RESPONSE":"DEFAULT";
    }

    private static final class WriteOp{
        final BluetoothGattCharacteristic c;
        final byte[] value;
        WriteOp(BluetoothGattCharacteristic c,byte[] value){
            this.c=c;
            this.value=value;
        }
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
