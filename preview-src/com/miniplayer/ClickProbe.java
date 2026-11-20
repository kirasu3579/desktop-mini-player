package com.miniplayer;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/** 最小化 Robot 点击测试：一个普通 JFrame，看事件能不能进来。 */
public final class ClickProbe {

    private ClickProbe() {
    }

    public static void main(String[] args) throws Exception {
        List<String> events = new ArrayList<>();
        JFrame[] holder = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            JFrame f = new JFrame("clickprobe");
            holder[0] = f;
            f.setUndecorated(true);
            f.setSize(400, 200);
            f.setLocation(300, 300);
            f.getContentPane().setBackground(java.awt.Color.ORANGE);
            f.getContentPane().addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    events.add("frame press " + e.getPoint());
                }
            });
            f.setVisible(true);
            f.toFront();
        });
        Thread.sleep(800);

        Robot robot = new Robot();
        System.out.println("pointer before = " + MouseInfo.getPointerInfo().getLocation());
        robot.mouseMove(400, 380);
        robot.waitForIdle();
        Thread.sleep(200);
        System.out.println("pointer after  = " + MouseInfo.getPointerInfo().getLocation());
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(80);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
        Thread.sleep(600);

        System.out.println("frame events = " + events.size());
        for (String s : events) {
            System.out.println("  " + s);
        }
        SwingUtilities.invokeAndWait(() -> holder[0].dispose());
        System.exit(0);
    }
}
