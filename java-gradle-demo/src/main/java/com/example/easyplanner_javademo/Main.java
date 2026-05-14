package com.example.easyplanner_javademo;

import com.example.easyplanner_app.EasyplannerApp;
import com.example.easyplanner_app.Point;

public final class Main {
    public static void main(String[] args) {
        Point a = new Point(0.0, 0.0);
        Point b = new Point(3.0, 4.0);
        double d = EasyplannerApp.distance(a, b);
        System.out.println("EasyplannerApp.distance = " + d);
    }
}
