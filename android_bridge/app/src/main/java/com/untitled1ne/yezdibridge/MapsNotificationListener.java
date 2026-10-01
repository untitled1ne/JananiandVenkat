package com.untitled1ne.yezdibridge;

import android.app.Notification;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MapsNotificationListener extends NotificationListenerService {
    private static final Pattern DIST=Pattern.compile("(?i)(\\d+(?:[.,]\\d+)?)\\s*(m|km|ft|mi)\\b");
    private static final long FORCE_REFRESH_MS=4500;

    private int lastTurn=-99;
    private int lastDtm=-1;
    private int lastDtd=-1;
    private long lastSentAt=0;

    @Override public void onListenerConnected(){
        super.onListenerConnected();
        BleBridgeManager.get(this).connectSavedDevice();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn){
        if(!"com.google.android.apps.maps".equals(sbn.getPackageName())) return;

        Notification n=sbn.getNotification();
        Bundle e=n.extras;
        String all=collectText(e);
        if(all.isEmpty()) return;

        int turn=parseTurn(all);
        List<Integer> distances=parseDistances(all);
        if(turn<0 || distances.isEmpty()) return;

        int dtm=distances.get(0);
        int dtd=distances.size()>1?distances.get(distances.size()-1):0;

        long now=SystemClock.elapsedRealtime();
        boolean changed=turn!=lastTurn ||
                Math.abs(dtm-lastDtm)>=5 ||
                (dtd>0 && Math.abs(dtd-lastDtd)>=20) ||
                now-lastSentAt>=FORCE_REFRESH_MS;
        if(!changed) return;

        lastTurn=turn;
        lastDtm=dtm;
        lastDtd=dtd;
        lastSentAt=now;

        BleBridgeManager.get(this).sendNavigation(turn,dtm,dtd);
    }

    private static String collectText(Bundle e){
        StringBuilder b=new StringBuilder();
        append(b,e.getCharSequence(Notification.EXTRA_TITLE));
        append(b,e.getCharSequence(Notification.EXTRA_TEXT));
        append(b,e.getCharSequence(Notification.EXTRA_BIG_TEXT));
        append(b,e.getCharSequence(Notification.EXTRA_SUB_TEXT));
        append(b,e.getCharSequence(Notification.EXTRA_INFO_TEXT));
        CharSequence[] lines=e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if(lines!=null) for(CharSequence s:lines) append(b,s);
        return b.toString().replace('•',' ').replace('·',' ').trim();
    }

    private static void append(StringBuilder b,CharSequence s){
        if(s==null) return;
        String x=s.toString().trim();
        if(x.isEmpty() || "null".equalsIgnoreCase(x)) return;
        if(b.length()>0) b.append(" | ");
        b.append(x);
    }

    private int parseTurn(String s){
        String x=s.toLowerCase(Locale.ROOT);

        // Prefer the earliest maneuver phrase. Google may include a "then ..." instruction too.
        int bestPos=Integer.MAX_VALUE;
        int best=-1;

        best=pick(x,bestPos,best,"make a u-turn",4); if(best>=0) bestPos=position(x,"make a u-turn",bestPos);
        int p=position(x,"u-turn",bestPos); if(p<bestPos){bestPos=p;best=4;}
        p=position(x,"u turn",bestPos); if(p<bestPos){bestPos=p;best=4;}

        p=position(x,"slight left",bestPos); if(p<bestPos){bestPos=p;best=2;}
        p=position(x,"slightly left",bestPos); if(p<bestPos){bestPos=p;best=2;}
        p=position(x,"keep left",bestPos); if(p<bestPos){bestPos=p;best=2;}
        p=position(x,"bear left",bestPos); if(p<bestPos){bestPos=p;best=2;}

        p=position(x,"slight right",bestPos); if(p<bestPos){bestPos=p;best=3;}
        p=position(x,"slightly right",bestPos); if(p<bestPos){bestPos=p;best=3;}
        p=position(x,"keep right",bestPos); if(p<bestPos){bestPos=p;best=3;}
        p=position(x,"bear right",bestPos); if(p<bestPos){bestPos=p;best=3;}

        p=position(x,"roundabout",bestPos); if(p<bestPos){bestPos=p;best=5;}

        p=position(x,"turn left",bestPos); if(p<bestPos){bestPos=p;best=1;}
        p=position(x,"take the left",bestPos); if(p<bestPos){bestPos=p;best=1;}
        p=position(x,"left onto",bestPos); if(p<bestPos){bestPos=p;best=1;}
        p=position(x,"↰",bestPos); if(p<bestPos){bestPos=p;best=1;}

        p=position(x,"turn right",bestPos); if(p<bestPos){bestPos=p;best=6;}
        p=position(x,"take the right",bestPos); if(p<bestPos){bestPos=p;best=6;}
        p=position(x,"right onto",bestPos); if(p<bestPos){bestPos=p;best=6;}
        p=position(x,"↱",bestPos); if(p<bestPos){bestPos=p;best=6;}

        p=position(x,"continue straight",bestPos); if(p<bestPos){bestPos=p;best=0;}
        p=position(x,"continue on",bestPos); if(p<bestPos){bestPos=p;best=0;}
        p=position(x,"head straight",bestPos); if(p<bestPos){bestPos=p;best=0;}
        p=position(x,"go straight",bestPos); if(p<bestPos){bestPos=p;best=0;}
        p=position(x,"↑",bestPos); if(p<bestPos){bestPos=p;best=0;}

        // Last-resort single words.
        if(best<0){
            if(x.contains("left")) return 1;
            if(x.contains("right")) return 6;
            if(x.contains("straight")||x.contains("continue")) return 0;
        }
        return best;
    }

    private int pick(String x,int currentPos,int current,String phrase,int code){
        int p=position(x,phrase,currentPos);
        return p<currentPos?code:current;
    }

    private int position(String x,String phrase,int max){
        int p=x.indexOf(phrase);
        return p>=0?p:max;
    }

    private List<Integer> parseDistances(String s){
        ArrayList<Integer> out=new ArrayList<>();
        Matcher m=DIST.matcher(s);
        while(m.find()){
            try{
                double v=Double.parseDouble(m.group(1).replace(',','.'));
                String unit=m.group(2).toLowerCase(Locale.ROOT);
                int meters;
                switch(unit){
                    case "km": meters=(int)Math.round(v*1000.0); break;
                    case "mi": meters=(int)Math.round(v*1609.344); break;
                    case "ft": meters=(int)Math.round(v*0.3048); break;
                    default: meters=(int)Math.round(v);
                }
                if(meters>=0 && meters<200000) out.add(meters);
            }catch(Exception ignored){}
        }
        return out;
    }
}
