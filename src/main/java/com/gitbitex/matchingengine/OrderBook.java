package com.gitbitex.matchingengine;

import com.alibaba.fastjson.JSON;
import com.gitbitex.matchingengine.command.CancelOrderCommand;
import com.gitbitex.matchingengine.command.NewOrderCommand;
import com.gitbitex.matchingengine.log.*;
import com.gitbitex.order.entity.Order;
import com.gitbitex.order.entity.Order.OrderSide;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Getter
@Slf4j
public class OrderBook implements Serializable {
    private static final long serialVersionUID = 1927816293532124184L;
    private final String productId;
    private final AtomicLong tradeId;
    private final AtomicLong sequence;
    private final SlidingBloomFilter orderIdFilter;
    private final BookPage asks;
    private final BookPage bids;
    private long commandOffset;
    private long logOffset;
    private boolean stable;

    public OrderBook(String productId) {
        this.productId = productId;
        this.tradeId = new AtomicLong();
        this.sequence = new AtomicLong();
        this.orderIdFilter = new SlidingBloomFilter(1000000, 2);
        this.asks = new BookPage(productId, Comparator.naturalOrder(), tradeId, sequence);
        this.bids = new BookPage(productId, Comparator.reverseOrder(), tradeId, sequence);
    }

    public List<OrderBookLog> executeCommand(NewOrderCommand command) {
        Order order = command.getOrder();

        // We must ensure that the order will not be processed repeatedly, but only partially.
        // If there is a wide range of order ID duplication, we need to increase the capacity of orderIdFilter
        if (orderIdFilter.contains(order.getOrderId())) {
            logger.warn("received repeated order: {}", JSON.toJSONString(order));
            return null;
        }
        orderIdFilter.put(order.getOrderId());

        if (order.getSide() == OrderSide.BUY) {
            return (asks.executeCommand(command, bids));
        } else {
            return (bids.executeCommand(command, asks));
        }
    }

    public OrderBookLog executeCommand(CancelOrderCommand message) {
        String orderId = message.getOrderId();
        BookOrder order = asks.getOrderById(orderId);
        if (order == null) {
            order = bids.getOrderById(orderId);
        }
        if (order == null) {
            return null;
        }

        return (order.getSide() == OrderSide.BUY ? bids : asks).executeCommand(message);
    }

    public PageLine restoreLog(OrderReceivedLog log) {
        this.sequence.set(log.getSequence());
        this.logOffset = log.getOffset();
        this.commandOffset = log.getCommandOffset();
        this.orderIdFilter.put(log.getOrder().getOrderId());
        this.stable = log.isCommandFinished();
        return null;
    }

    public PageLine restoreLog(OrderOpenLog log) {
        this.sequence.set(log.getSequence());
        this.logOffset = log.getOffset();
        this.commandOffset = log.getCommandOffset();
        this.stable = log.isCommandFinished();
        BookOrder order = new BookOrder();
        order.setOrderId(log.getOrderId());
        order.setPrice(log.getPrice());
        order.setSize(log.getRemainingSize());
        order.setSide(log.getSide());
        order.setUserId(log.getUserId());
        return this.addOrder(order);
    }

    public PageLine restoreLog(OrderMatchLog log) {
        this.sequence.set(log.getSequence());
        this.logOffset = log.getOffset();
        this.commandOffset = log.getCommandOffset();
        this.tradeId.set(log.getTradeId());
        this.stable = log.isCommandFinished();
        return this.decreaseOrderSize(log.getMakerOrderId(), log.getSide(), log.getSize());
    }

    public PageLine restoreLog(OrderDoneLog log) {
        this.sequence.set(log.getSequence());
        this.logOffset = log.getOffset();
        this.commandOffset = log.getCommandOffset();
        this.stable = log.isCommandFinished();
        if (log.getPrice() != null) {
            return this.removeOrderById(log.getOrderId(), log.getSide());
        }
        return null;
    }

    private PageLine addOrder(BookOrder order) {
        return (order.getSide() == OrderSide.BUY ? bids : asks).addOrder(order);
    }

    private PageLine decreaseOrderSize(String orderId, OrderSide side, BigDecimal size) {
        return (side == OrderSide.BUY ? bids : asks).decreaseOrderSize(orderId, size);
    }

    private PageLine removeOrderById(String orderId, OrderSide side) {
        return (side == OrderSide.BUY ? bids : asks).removeOrderById(orderId);
    }
}
