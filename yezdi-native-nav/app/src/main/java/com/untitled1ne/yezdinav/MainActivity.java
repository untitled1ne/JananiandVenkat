package com.untitled1ne.yezdinav;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import com.google.android.gms.maps.*;
import com.google.android.gms.maps.model.*;

import org.json.*;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity implements OnMapReadyCallback, LocationListener {
    private static final int REQ_PERMS = 44;
    private static final String ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes";

    private MapView mapView;
    private GoogleMap map;
    private LocationManager locationManager;
    private Location currentLocation;
    private boolean centeredOnce = false;

    private EditText searchBox;
    private TextView bikeStatus, routeStatus, instructionText, nextDistanceText, remainingText, arrowText;
    private Button connectButton, startButton;
    private Marker destinationMarker;
    private Polyline routeLine;
    private LatLng destination;
    private final ArrayList<RouteStep> steps = new ArrayList<>();
    private int currentStepIndex = 0;
    private boolean navigationActive = false;
    private int routeDistanceMeters = 0;
    private long lastRouteRefresh = 0L;
    private long lastBikeSend = 0L;
    private int lastBikeTurn = -999, lastBikeDtm = -999, lastBikeDtd = -999;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private String routesKey = "";

    private Object bleManager;
    private Object bleListenerProxy;
    private Method bleSendNavigation, bleConnect, bleConnectSaved, bleHasSaved, bleAddListener, bleRemoveListener;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner scanner;
    private final LinkedHashMap<String, BluetoothDevice> scanned = new LinkedHashMap<>();

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        routesKey = readAssetText("routes_key.txt").trim();
        buildUi();
        mapView.onCreate(b);
        mapView.getMapAsync(this);
        initBleBridge();
        askPermissions();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(238,242,247));

        mapView = new MapView(this);
        root.addView(mapView, new FrameLayout.LayoutParams(-1,-1));

        LinearLayout top = card(true);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(14),dp(12),dp(14),dp(12));
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(-1,-2);
        topLp.setMargins(dp(12),dp(18),dp(12),0);
        topLp.gravity = Gravity.TOP;
        root.addView(top, topLp);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("YEZDI NAV", 18, Color.rgb(18,31,48), true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0,-2,1));
        bikeStatus = pill("BIKE OFFLINE", Color.rgb(238,238,238), Color.rgb(70,70,70));
        titleRow.addView(bikeStatus);
        top.addView(titleRow);

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(0,dp(10),0,0);
        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setHint("Where to?");
        searchBox.setTextSize(16);
        searchBox.setPadding(dp(14),0,dp(10),0);
        searchBox.setBackground(round(Color.rgb(245,247,250), 14));
        searchRow.addView(searchBox, new LinearLayout.LayoutParams(0,dp(48),1));

        Button go = smallButton("GO", Color.rgb(30,122,255));
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(dp(64),dp(48));
        goLp.setMargins(dp(8),0,0,0);
        searchRow.addView(go,goLp);
        go.setOnClickListener(v -> searchDestination());
        searchBox.setOnEditorActionListener((v,a,e)->{ searchDestination(); return true; });
        top.addView(searchRow);

        LinearLayout connectionRow = new LinearLayout(this);
        connectionRow.setPadding(0,dp(8),0,0);
        connectButton = smallButton("CONNECT BIKE", Color.rgb(20,35,52));
        connectionRow.addView(connectButton, new LinearLayout.LayoutParams(0,dp(42),1));
        Button recenter = smallButton("◎", Color.rgb(82,94,108));
        LinearLayout.LayoutParams rcLp = new LinearLayout.LayoutParams(dp(52),dp(42));
        rcLp.setMargins(dp(8),0,0,0);
        connectionRow.addView(recenter,rcLp);
        top.addView(connectionRow);
        connectButton.setOnClickListener(v -> showBikeConnectOptions());
        recenter.setOnClickListener(v -> centerOnCurrent());

        LinearLayout bottom = card(true);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(16),dp(14),dp(16),dp(14));
        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(-1,-2);
        bottomLp.setMargins(dp(12),0,dp(12),dp(18));
        bottomLp.gravity = Gravity.BOTTOM;
        root.addView(bottom,bottomLp);

        routeStatus = label("LONG-PRESS MAP OR SEARCH A DESTINATION", 11, Color.rgb(95,106,118), true);
        bottom.addView(routeStatus);

        LinearLayout navRow = new LinearLayout(this);
        navRow.setGravity(Gravity.CENTER_VERTICAL);
        navRow.setPadding(0,dp(8),0,0);
        arrowText = label("↑", 46, Color.rgb(18,31,48), true);
        arrowText.setGravity(Gravity.CENTER);
        navRow.addView(arrowText,new LinearLayout.LayoutParams(dp(64),dp(70)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        instructionText = label("Ready", 20, Color.rgb(18,31,48), true);
        nextDistanceText = label("Select a destination", 14, Color.rgb(74,87,102), false);
        texts.addView(instructionText);
        texts.addView(nextDistanceText);
        navRow.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        bottom.addView(navRow);

        LinearLayout stats = new LinearLayout(this);
        stats.setGravity(Gravity.CENTER_VERTICAL);
        remainingText = label("— remaining", 14, Color.rgb(18,31,48), true);
        stats.addView(remainingText,new LinearLayout.LayoutParams(0,-2,1));
        startButton = smallButton("START RIDE", Color.rgb(15,166,94));
        startButton.setEnabled(false);
        stats.addView(startButton,new LinearLayout.LayoutParams(dp(132),dp(46)));
        bottom.addView(stats);
        startButton.setOnClickListener(v -> toggleNavigation());

        TextView hint = label("Tip: long-press anywhere on the map to change destination.", 11, Color.rgb(120,130,140), false);
        hint.setPadding(0,dp(8),0,0);
        bottom.addView(hint);

        setContentView(root);
    }

    private LinearLayout card(boolean shadow) {
        LinearLayout v = new LinearLayout(this);
        v.setBackground(round(Color.WHITE,18));
        if (Build.VERSION.SDK_INT >= 21 && shadow) v.setElevation(dp(6));
        return v;
    }
    private TextView label(String s,int sp,int color,boolean bold){
        TextView t=new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return t;
    }
    private TextView pill(String s,int bg,int fg){
        TextView t=label(s,11,fg,true); t.setPadding(dp(10),dp(6),dp(10),dp(6)); t.setBackground(round(bg,999)); return t;
    }
    private Button smallButton(String s,int color){
        Button b=new Button(this); b.setText(s); b.setTextColor(Color.WHITE); b.setTextSize(12); b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setAllCaps(false); b.setBackground(round(color,13)); return b;
    }
    private GradientDrawable round(int color,float radiusDp){
        GradientDrawable g=new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp((int)radiusDp)); return g;
    }
    private int dp(int v){ return (int)(v*getResources().getDisplayMetrics().density+0.5f); }

    @Override public void onMapReady(GoogleMap g) {
        map=g;
        map.getUiSettings().setCompassEnabled(false);
        map.getUiSettings().setMapToolbarEnabled(false);
        map.getUiSettings().setMyLocationButtonEnabled(false);
        map.setOnMapLongClickListener(p -> setDestination(p,"Pinned destination"));
        enableMyLocation();
    }

    private void askPermissions(){
        ArrayList<String> p=new ArrayList<>();
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if(Build.VERSION.SDK_INT>=31){
            if(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.BLUETOOTH_SCAN);
            if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if(!p.isEmpty()) requestPermissions(p.toArray(new String[0]),REQ_PERMS); else startLocation();
    }
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){
        super.onRequestPermissionsResult(r,p,g); startLocation(); enableMyLocation();
    }

    private void startLocation(){
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED &&
           checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED) return;
        locationManager=(LocationManager)getSystemService(LOCATION_SERVICE);
        try{ locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1500,2,this); }catch(Exception ignored){}
        try{ locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,2500,5,this); }catch(Exception ignored){}
        Location a=null,b=null;
        try{a=locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);}catch(Exception ignored){}
        try{b=locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);}catch(Exception ignored){}
        if(a!=null || b!=null) onLocationChanged(a!=null?a:b);
    }

    private void enableMyLocation(){
        if(map==null)return;
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
           checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED){
            try{map.setMyLocationEnabled(true);}catch(Exception ignored){}
        }
    }
    private void centerOnCurrent(){
        if(map!=null && currentLocation!=null){
            LatLng p=new LatLng(currentLocation.getLatitude(),currentLocation.getLongitude());
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(p,16.2f));
        }
    }
    @Override public void onLocationChanged(Location l){
        if(l==null)return; currentLocation=l;
        if(!centeredOnce){ centeredOnce=true; centerOnCurrent(); }
        if(navigationActive && destination!=null){
            updateNavigationFromLocation();
            long now=SystemClock.elapsedRealtime();
            if(now-lastRouteRefresh>45000){ lastRouteRefresh=now; fetchRoute(false); }
        }
    }
    @Override public void onProviderEnabled(String p){}
    @Override public void onProviderDisabled(String p){}
    @Deprecated @Override public void onStatusChanged(String p,int s,Bundle e){}

    private void searchDestination(){
        final String q=searchBox.getText().toString().trim();
        if(q.isEmpty())return;
        hideKeyboard();
        routeStatus.setText("SEARCHING…");
        executor.execute(() -> {
            try{
                Geocoder geocoder=new Geocoder(this,Locale.getDefault());
                List<Address> list=geocoder.getFromLocationName(q,5);
                if(list==null || list.isEmpty()) throw new Exception("No result");
                Address a=list.get(0);
                LatLng p=new LatLng(a.getLatitude(),a.getLongitude());
                String name=a.getFeatureName()!=null?a.getFeatureName():q;
                main.post(() -> setDestination(p,name));
            }catch(Exception e){ main.post(() -> toast("Could not find that destination")); }
        });
    }
    private void hideKeyboard(){
        try{((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(searchBox.getWindowToken(),0);}catch(Exception ignored){}
    }
    private void setDestination(LatLng p,String name){
        destination=p;
        if(destinationMarker!=null)destinationMarker.remove();
        destinationMarker=map.addMarker(new MarkerOptions().position(p).title(name));
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(p,15.5f));
        navigationActive=false; startButton.setText("START RIDE");
        routeStatus.setText("CALCULATING ROUTE…");
        instructionText.setText(name);
        fetchRoute(true);
    }

    private void fetchRoute(boolean fitBounds){
        if(destination==null || currentLocation==null){ routeStatus.setText("WAITING FOR GPS…"); return; }
        final double olat=currentLocation.getLatitude(), olng=currentLocation.getLongitude();
        final LatLng dest=destination;
        executor.execute(() -> {
            try{
                RouteResult rr=requestRoute(olat,olng,dest.latitude,dest.longitude,"TWO_WHEELER");
                if(rr==null) rr=requestRoute(olat,olng,dest.latitude,dest.longitude,"DRIVE");
                if(rr==null) throw new Exception("No route");
                final RouteResult result=rr;
                main.post(() -> applyRoute(result,fitBounds));
            }catch(Exception e){ main.post(() -> { routeStatus.setText("ROUTE FAILED"); toast("Routes API: "+e.getMessage()); }); }
        });
    }

    private RouteResult requestRoute(double olat,double olng,double dlat,double dlng,String mode) throws Exception {
        URL u=new URL(ROUTES_URL);
        HttpURLConnection c=(HttpURLConnection)u.openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(12000); c.setReadTimeout(12000);
        c.setRequestProperty("Content-Type","application/json");
        c.setRequestProperty("X-Goog-Api-Key",routesKey);
        c.setRequestProperty("X-Goog-FieldMask","routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline,routes.legs.steps.distanceMeters,routes.legs.steps.startLocation,routes.legs.steps.endLocation,routes.legs.steps.navigationInstruction");
        JSONObject body=new JSONObject();
        body.put("origin",waypoint(olat,olng)); body.put("destination",waypoint(dlat,dlng));
        body.put("travelMode",mode); body.put("routingPreference","TRAFFIC_AWARE");
        body.put("polylineQuality","OVERVIEW");
        byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);
        try(OutputStream os=c.getOutputStream()){os.write(data);}
        int code=c.getResponseCode();
        InputStream is=code>=200&&code<300?c.getInputStream():c.getErrorStream();
        String txt=readAll(is);
        if(code<200||code>=300){ if("TWO_WHEELER".equals(mode)) return null; throw new Exception("HTTP "+code+" "+shorten(txt)); }
        JSONObject root=new JSONObject(txt); JSONArray routes=root.optJSONArray("routes");
        if(routes==null||routes.length()==0)return null;
        JSONObject r=routes.getJSONObject(0); RouteResult out=new RouteResult();
        out.distanceMeters=r.optInt("distanceMeters",0);
        JSONObject pol=r.optJSONObject("polyline"); if(pol!=null) out.encodedPolyline=pol.optString("encodedPolyline","");
        JSONArray legs=r.optJSONArray("legs");
        if(legs!=null&&legs.length()>0){
            JSONArray st=legs.getJSONObject(0).optJSONArray("steps");
            if(st!=null) for(int i=0;i<st.length();i++){
                JSONObject s=st.getJSONObject(i); RouteStep rs=new RouteStep();
                rs.distanceMeters=s.optInt("distanceMeters",0);
                JSONObject ni=s.optJSONObject("navigationInstruction");
                if(ni!=null){rs.maneuver=ni.optString("maneuver","STRAIGHT"); rs.instructions=ni.optString("instructions","Continue");}
                else {rs.maneuver="STRAIGHT";rs.instructions="Continue";}
                rs.end=latLngFrom(s.optJSONObject("endLocation"));
                if(rs.end!=null)out.steps.add(rs);
            }
        }
        return out;
    }
    private JSONObject waypoint(double lat,double lng) throws JSONException{
        JSONObject ll=new JSONObject().put("latitude",lat).put("longitude",lng);
        return new JSONObject().put("location",new JSONObject().put("latLng",ll));
    }
    private LatLng latLngFrom(JSONObject loc){
        if(loc==null)return null; JSONObject ll=loc.optJSONObject("latLng"); if(ll==null)return null;
        return new LatLng(ll.optDouble("latitude"),ll.optDouble("longitude"));
    }

    private void applyRoute(RouteResult r,boolean fitBounds){
        routeDistanceMeters=r.distanceMeters;
        steps.clear();steps.addAll(r.steps);currentStepIndex=0;
        if(routeLine!=null)routeLine.remove();
        List<LatLng> pts=decodePolyline(r.encodedPolyline);
        if(!pts.isEmpty()) routeLine=map.addPolyline(new PolylineOptions().addAll(pts).width(dp(5)).color(Color.rgb(25,103,232)).geodesic(false));
        routeStatus.setText("ROUTE READY");
        remainingText.setText(formatDistance(routeDistanceMeters)+" remaining");
        startButton.setEnabled(!steps.isEmpty());
        if(!steps.isEmpty())showStep(0);
        if(fitBounds && !pts.isEmpty()){
            LatLngBounds.Builder bb=new LatLngBounds.Builder();
            for(LatLng p:pts)bb.include(p);
            try{map.animateCamera(CameraUpdateFactory.newLatLngBounds(bb.build(),dp(72)));}catch(Exception ignored){}
        }
        if(navigationActive) updateNavigationFromLocation();
    }

    private void toggleNavigation(){
        if(destination==null||steps.isEmpty())return;
        navigationActive=!navigationActive;
        startButton.setText(navigationActive?"STOP":"START RIDE");
        routeStatus.setText(navigationActive?"NAVIGATING • LIVE TFT":"ROUTE READY");
        if(navigationActive){lastRouteRefresh=0; updateNavigationFromLocation();}
    }

    private void updateNavigationFromLocation(){
        if(currentLocation==null||steps.isEmpty())return;
        while(currentStepIndex<steps.size()-1){
            RouteStep s=steps.get(currentStepIndex);
            float d=distanceTo(s.end);
            if(d<35)currentStepIndex++; else break;
        }
        RouteStep s=steps.get(Math.min(currentStepIndex,steps.size()-1));
        int dtm=Math.max(0,Math.round(distanceTo(s.end)));
        int dtd=dtm;
        for(int i=currentStepIndex+1;i<steps.size();i++)dtd+=Math.max(0,steps.get(i).distanceMeters);
        dtd=Math.max(0,dtd);
        showStep(currentStepIndex);
        nextDistanceText.setText(formatDistance(dtm)+" to next turn");
        remainingText.setText(formatDistance(dtd)+" remaining");
        int turn=turnCode(s.maneuver);
        sendBike(turn,dtm,dtd);
        if(dtd<25){routeStatus.setText("ARRIVING");}
    }

    private void showStep(int i){
        if(i<0||i>=steps.size())return; RouteStep s=steps.get(i);
        instructionText.setText(cleanInstruction(s.instructions));
        arrowText.setText(arrowFor(s.maneuver));
        if(!navigationActive) nextDistanceText.setText(formatDistance(s.distanceMeters)+" first step");
    }
    private int turnCode(String m){
        String x=m==null?"":m.toUpperCase(Locale.US);
        if(x.contains("UTURN"))return 4;
        if(x.contains("ROUNDABOUT"))return 5;
        if(x.contains("SLIGHT_LEFT")||x.contains("FORK_LEFT")||x.contains("KEEP_LEFT")||x.contains("RAMP_LEFT"))return 2;
        if(x.contains("SLIGHT_RIGHT")||x.contains("FORK_RIGHT")||x.contains("KEEP_RIGHT")||x.contains("RAMP_RIGHT"))return 3;
        if(x.contains("LEFT"))return 1;
        if(x.contains("RIGHT"))return 6;
        return 0;
    }
    private String arrowFor(String m){
        int c=turnCode(m); if(c==1)return "↰"; if(c==2)return "↖"; if(c==3)return "↗"; if(c==4)return "⤵"; if(c==5)return "⟳"; if(c==6)return "↱"; return "↑";
    }
    private String cleanInstruction(String s){ return s==null||s.trim().isEmpty()?"Continue":s.replaceAll("<[^>]+>","").trim(); }
    private float distanceTo(LatLng p){
        if(p==null||currentLocation==null)return 0; float[] r=new float[1];
        Location.distanceBetween(currentLocation.getLatitude(),currentLocation.getLongitude(),p.latitude,p.longitude,r);return r[0];
    }

    private void initBleBridge(){
        try{
            Class<?> cls=Class.forName("com.untitled1ne.yezdibridge.BleBridgeManager");
            Method get=cls.getDeclaredMethod("get",Context.class); get.setAccessible(true);
            bleManager=get.invoke(null,getApplicationContext());
            bleSendNavigation=cls.getDeclaredMethod("sendNavigation",int.class,int.class,int.class); bleSendNavigation.setAccessible(true);
            bleConnect=cls.getDeclaredMethod("connect",BluetoothDevice.class); bleConnect.setAccessible(true);
            bleConnectSaved=cls.getDeclaredMethod("connectSavedDevice"); bleConnectSaved.setAccessible(true);
            bleHasSaved=cls.getDeclaredMethod("hasSavedDevice"); bleHasSaved.setAccessible(true);
            Class<?> li=Class.forName("com.untitled1ne.yezdibridge.BleBridgeManager$Listener");
            bleAddListener=cls.getDeclaredMethod("addListener",li);bleAddListener.setAccessible(true);
            bleRemoveListener=cls.getDeclaredMethod("removeListener",li);bleRemoveListener.setAccessible(true);
            bleListenerProxy=java.lang.reflect.Proxy.newProxyInstance(li.getClassLoader(),new Class[]{li},(proxy,method,args)->{
                if("onConnected".equals(method.getName())&&args!=null&&args.length>0){
                    boolean yes=(Boolean)args[0]; runOnUiThread(()->setBikeConnected(yes));
                } else if("onLog".equals(method.getName())&&args!=null&&args.length>0){
                    String msg=String.valueOf(args[0]);
                    if(msg.contains("authorization handshake accepted"))runOnUiThread(()->routeStatus.setText(navigationActive?"NAVIGATING • TFT CONNECTED":"BIKE AUTHORIZED"));
                }
                return null;
            });
            bleAddListener.invoke(bleManager,bleListenerProxy);
            boolean saved=(Boolean)bleHasSaved.invoke(bleManager);
            bikeStatus.setText(saved?"BIKE SAVED":"BIKE OFFLINE");
        }catch(Throwable t){
            bikeStatus.setText("BLE MODULE ERROR");
            connectButton.setEnabled(false);
        }
        BluetoothManager bm=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter=bm!=null?bm.getAdapter():null;
    }
    private void setBikeConnected(boolean yes){
        bikeStatus.setText(yes?"BIKE CONNECTED":"BIKE OFFLINE");
        bikeStatus.setTextColor(yes?Color.rgb(0,110,65):Color.rgb(70,70,70));
        bikeStatus.setBackground(round(yes?Color.rgb(210,250,228):Color.rgb(238,238,238),999));
    }
    private void showBikeConnectOptions(){
        if(bleManager==null){toast("BLE module unavailable");return;}
        ArrayList<String> opts=new ArrayList<>();
        try{ if((Boolean)bleHasSaved.invoke(bleManager))opts.add("Reconnect saved MY YEZDI"); }catch(Exception ignored){}
        opts.add("Scan and pair MY YEZDI");
        new AlertDialog.Builder(this).setTitle("Connect bike").setItems(opts.toArray(new String[0]),(d,w)->{
            if(opts.get(w).startsWith("Reconnect"))connectSavedBike();else scanForBike();
        }).show();
    }
    private void connectSavedBike(){
        try{boolean ok=(Boolean)bleConnectSaved.invoke(bleManager); if(!ok)toast("Could not connect saved bike"); else bikeStatus.setText("CONNECTING…");}
        catch(Exception e){toast("Reconnect failed");}
    }
    private void scanForBike(){
        if(bluetoothAdapter==null||!bluetoothAdapter.isEnabled()){toast("Turn Bluetooth on first");return;}
        if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED){askPermissions();return;}
        scanned.clear(); scanner=bluetoothAdapter.getBluetoothLeScanner(); if(scanner==null){toast("BLE scanner unavailable");return;}
        bikeStatus.setText("SCANNING…");
        try{scanner.startScan(scanCb);}catch(Exception e){toast("Scan failed");return;}
        main.postDelayed(()->{
            try{scanner.stopScan(scanCb);}catch(Exception ignored){}
            if(scanned.isEmpty()){bikeStatus.setText("BIKE OFFLINE");toast("MY YEZDI not found");return;}
            ArrayList<BluetoothDevice> devs=new ArrayList<>(scanned.values());
            String[] names=new String[devs.size()];
            for(int i=0;i<devs.size();i++){BluetoothDevice x=devs.get(i);String n=safeName(x);names[i]=(n==null?"MY YEZDI":n)+" • "+safeAddress(x);}
            new AlertDialog.Builder(this).setTitle("Select Yezdi").setItems(names,(d,w)->connectBike(devs.get(w))).show();
        },9000);
    }
    private final ScanCallback scanCb=new ScanCallback(){
        @Override public void onScanResult(int type,ScanResult r){
            BluetoothDevice d=r.getDevice(); String n=safeName(d); if(n==null&&r.getScanRecord()!=null)n=r.getScanRecord().getDeviceName();
            if(n!=null&&n.toUpperCase(Locale.US).contains("YEZDI"))scanned.put(safeAddress(d),d);
        }
    };
    private String safeName(BluetoothDevice d){try{if(Build.VERSION.SDK_INT<31||checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED)return d.getName();}catch(Exception ignored){}return null;}
    private String safeAddress(BluetoothDevice d){try{return d.getAddress();}catch(Exception e){return "";}}
    private void connectBike(BluetoothDevice d){
        try{bleConnect.invoke(bleManager,d);bikeStatus.setText("CONNECTING…");}catch(Exception e){toast("Bike connection failed");}
    }
    private void sendBike(int turn,int dtm,int dtd){
        if(bleManager==null||bleSendNavigation==null)return;
        long now=SystemClock.elapsedRealtime();
        boolean changed=turn!=lastBikeTurn||Math.abs(dtm-lastBikeDtm)>=10||Math.abs(dtd-lastBikeDtd)>=25;
        if(!changed&&now-lastBikeSend<5000)return;
        try{
            bleSendNavigation.invoke(bleManager,turn,dtm,dtd);
            lastBikeTurn=turn;lastBikeDtm=dtm;lastBikeDtd=dtd;lastBikeSend=now;
        }catch(Exception ignored){}
    }

    private String readAssetText(String name){
        try{return readAll(getAssets().open(name));}catch(Exception e){return "";}
    }
    private String readAll(InputStream in)throws IOException{
        if(in==null)return ""; ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;
        while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");
    }
    private String shorten(String s){if(s==null)return "";s=s.replace("\n"," ");return s.length()>180?s.substring(0,180):s;}
    private String formatDistance(int m){ if(m>=1000)return String.format(Locale.getDefault(),"%.1f km",m/1000.0); return m+" m"; }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}

    static List<LatLng> decodePolyline(String encoded){
        ArrayList<LatLng> poly=new ArrayList<>();if(encoded==null)return poly;int index=0,lat=0,lng=0;
        while(index<encoded.length()){
            int b,shift=0,result=0;do{b=encoded.charAt(index++)-63;result|=(b&0x1f)<<shift;shift+=5;}while(b>=0x20&&index<encoded.length());
            int dlat=((result&1)!=0?~(result>>1):(result>>1));lat+=dlat;shift=0;result=0;
            do{b=encoded.charAt(index++)-63;result|=(b&0x1f)<<shift;shift+=5;}while(b>=0x20&&index<encoded.length());
            int dlng=((result&1)!=0?~(result>>1):(result>>1));lng+=dlng;poly.add(new LatLng(lat/1E5,lng/1E5));
        }return poly;
    }
    static class RouteStep{int distanceMeters;String maneuver,instructions;LatLng end;}
    static class RouteResult{int distanceMeters;String encodedPolyline="";ArrayList<RouteStep> steps=new ArrayList<>();}

    @Override protected void onResume(){super.onResume();mapView.onResume();}
    @Override protected void onPause(){mapView.onPause();super.onPause();}
    @Override public void onLowMemory(){super.onLowMemory();mapView.onLowMemory();}
    @Override protected void onDestroy(){
        try{if(locationManager!=null)locationManager.removeUpdates(this);}catch(Exception ignored){}
        try{if(bleRemoveListener!=null&&bleManager!=null&&bleListenerProxy!=null)bleRemoveListener.invoke(bleManager,bleListenerProxy);}catch(Exception ignored){}
        executor.shutdownNow();mapView.onDestroy();super.onDestroy();
    }
}
