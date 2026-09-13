package com.hyfuse.bridge.dispatch;

import java.lang.reflect.Method;

/**
 * Regression test: window-slot mapping helpers.
 * With the broken mappings equip SWAP sites clicked invIndex+9 and
 * nodeToMcSlot returned raw Inventory indices — clicks landed on the
 * crafting grid / armor / result slots (destroyed a crafting_table; kicked
 * the client). These tests pin the corrected mappings.
 */
public class SlotMapTest {
    static int fails = 0;

    static void check(String name, Object got, Object want) {
        boolean ok = String.valueOf(got).equals(String.valueOf(want));
        if (!ok) { fails++; System.out.println("FAIL " + name + ": got " + got + " want " + want); }
        else System.out.println("ok   " + name + " = " + got);
    }

    public static void main(String[] args) throws Exception {
        Method inv2win = ToolDispatcher.class.getDeclaredMethod("invSlotToWindow", int.class);
        inv2win.setAccessible(true);
        Method node2mc = ToolDispatcher.class.getDeclaredMethod("nodeToMcSlot", int.class);
        node2mc.setAccessible(true);

        // invSlotToWindow: Inventory index -> InventoryMenu window slot
        check("hotbar0->36",  inv2win.invoke(null, 0), 36);
        check("hotbar8->44",  inv2win.invoke(null, 8), 44);
        check("main9->9",     inv2win.invoke(null, 9), 9);
        check("main19->19",   inv2win.invoke(null, 19), 19);
        check("main35->35",   inv2win.invoke(null, 35), 35);
        check("armorHead39->5",  inv2win.invoke(null, 39), 5);
        check("armorChest38->6", inv2win.invoke(null, 38), 6);
        check("armorLegs37->7",  inv2win.invoke(null, 37), 7);
        check("armorFeet36->8",  inv2win.invoke(null, 36), 8);
        check("offhand40->45",   inv2win.invoke(null, 40), 45);

        // nodeToMcSlot: its input convention IS window convention (identity),
        // except direct-hotbar shorthand 0-8 which maps +36.
        check("node9->9",     node2mc.invoke(null, 9), 9);
        check("node19->19",   node2mc.invoke(null, 19), 19);
        check("node35->35",   node2mc.invoke(null, 35), 35);
        check("node36->36",   node2mc.invoke(null, 36), 36); // hotbar slot 0 via direct-hotbar shorthand
        check("node44->44",   node2mc.invoke(null, 44), 44); // hotbar slot 8
        check("node45->45",   node2mc.invoke(null, 45), 45); // offhand
        check("nodeDirect0->36",  node2mc.invoke(null, 0), 36); // direct hotbar index 0
        check("nodeDirect8->44",  node2mc.invoke(null, 8), 44); // direct hotbar index 8

        if (fails > 0) { System.out.println(fails + " FAILURES"); System.exit(1); }
        System.out.println("\nALL CHECKS PASSED");
    }
}
