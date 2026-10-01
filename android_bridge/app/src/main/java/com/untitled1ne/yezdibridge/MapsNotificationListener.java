package com.untitled1ne.yezdibridge;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.Locale;
import java.util.regex.*;

public class MapsNotificationListener extends NotificationListenerService {
    private static final Pattern DIST=Pattern.compile("(?i)(\\d+(?:[.,]\\d+)?)\\s*(m|km)\\b");

    @Override public void onNotificationPosted(StatusBarNotification sbn){
        if(!"com.google.android.apps.maps".equals(sbn.getPackageName())) return;
        Notification n=sbn.getNotification();
        Bundle e=n.extras;
        String title=String.valueOf(e.getCharSequence(Notification.EXTRA_TITLE,""));
        String text=String.valueOf(e.getCharSequence(Notification.EXTRA_TEXT,""));
        String big=String.valueOf(e.getCharSequence(Notification.EXTRA_BIG_TEXT,""));
        String all=(title+" "+text+" "+big).trim();
        if(all.isEmpty()) return;
        int turn=parseTurn(all);
        int meters=parseDistance(all);
        if(turn>=0 && meters>=0){
            BleBridgeManager.get(this).sendNavigation(turn,meters,0);
        }
    }

    private int parseTurn(String s){
        String x=s.toLowerCase(Locale.ROOT);
        if(x.contains("u-turn")||x.contains("u turn")) return 4;
        if(x.contains("roundabout")) return 5;
        if(x.contains("slight left")) return 2;
        if(x.contains("slight right")) return 3;
        if(x.contains("left")) return 1;
        if(x.contains("right")) return 6;
        if(x.contains("straight")||x.contains("continue")) return 0;
        return -1;
    }

    private int parseDistance(String s){
        Matcher m=DIST.matcher(s);
        if(!m.find()) return -1;
        double v=Double.parseDouble(m.group(1).replace(',','.'));
        return "km".equalsIgnoreCase(m.group(2))?(int)Math.round(v*1000.0):(int)Math.round(v);
    }
}
