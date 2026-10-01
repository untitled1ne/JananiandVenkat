package com.untitled1ne.yezdibridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity implements BleBridgeManager.Listener {
    private TextView status, log;
    private LinearLayout devices;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private final Set<String> seen = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        status=findViewById(R.id.status);
        log=findViewById(R.id.log);
        devices=findViewById(R.id.devices);
        BluetoothManager bm=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter=bm.getAdapter();
        BleBridgeManager.get(this).addListener(this);
        findViewById(R.id.permissions).setOnClickListener(v->askPermissions());
        findViewById(R.id.notificationAccess).setOnClickListener(v->startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        findViewById(R.id.scan).setOnClickListener(v->scan());
        findViewById(R.id.testLeft).setOnClickListener(v->BleBridgeManager.get(this).sendNavigation(1,180,7400));
        findViewById(R.id.testRight).setOnClickListener(v->BleBridgeManager.get(this).sendNavigation(6,180,7400));
        append("Bridge v0.1 ready. Close the official Yezdi app before scanning so it does not hold the BLE connection.");
    }

    private void askPermissions(){
        ArrayList<String> p=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=31){
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if(Build.VERSION.SDK_INT>=33) p.add(Manifest.permission.POST_NOTIFICATIONS);
        requestPermissions(p.toArray(new String[0]),100);
    }

    @SuppressLint("MissingPermission") private void scan(){
        if(adapter==null || !adapter.isEnabled()){
            append("Enable Bluetooth first.");
            return;
        }
        if(Build.VERSION.SDK_INT>=31 &&
          (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED ||
           checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)){
            askPermissions();
            return;
        }
        devices.removeAllViews();
        seen.clear();
        scanner=adapter.getBluetoothLeScanner();
        append("Scanning for 12 seconds...");
        scanner.startScan(cb);
        handler.postDelayed(()->{
            try{scanner.stopScan(cb);}catch(Exception ignored){}
            append("Scan finished.");
        },12000);
        for(BluetoothDevice d:adapter.getBondedDevices()) addDevice(d,"BONDED");
    }

    private final ScanCallback cb=new ScanCallback(){
        @Override public void onScanResult(int t, ScanResult r){
            addDevice(r.getDevice(),"RSSI "+r.getRssi());
        }
    };

    @SuppressLint("MissingPermission") private void addDevice(BluetoothDevice d,String extra){
        String addr=d.getAddress();
        if(!seen.add(addr)) return;
        String name=d.getName();
        if(name==null) name="Unnamed BLE device";
        Button b=new Button(this);
        b.setAllCaps(false);
        b.setText(name+"\n"+addr+" · "+extra);
        b.setOnClickListener(v->BleBridgeManager.get(this).connect(d));
        runOnUiThread(()->devices.addView(b));
    }

    private void append(String s){
        runOnUiThread(()->log.append((log.length()==0?"":"\n")+s));
    }

    @Override public void onLog(String s){ append(s); }

    @Override public void onConnected(boolean ok){
        runOnUiThread(()->status.setText(ok?"Connected — discovering Yezdi services":"Not connected"));
    }

    @Override protected void onDestroy(){
        BleBridgeManager.get(this).removeListener(this);
        super.onDestroy();
    }
}
