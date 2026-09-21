package com.example.smcshop;

record ShopResult(
    String item,
    String owner,
    String type,
    int amount,
    double price,
    String world,
    int x,
    int y,
    int z,
    String nearestPort,
    int nearestPortDistance,
    String observedAt
) {
    boolean isSell() {
        return "SELL".equalsIgnoreCase(type);
    }

    String typeLabel() {
        return isSell() ? "出售" : "收购";
    }
}
