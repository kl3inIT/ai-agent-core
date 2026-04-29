package com.vn.jmixapp.view.order;

import com.vn.jmixapp.entity.Order;
import com.vn.jmixapp.view.main.MainView;
import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.*;

@Route(value = "orders", layout = MainView.class)
@ViewController(id = "Order.list")
@ViewDescriptor(path = "order-list-view.xml")
@LookupComponent("ordersDataGrid")
@DialogMode(width = "64em")
@PrimaryListView(Order.class)
public class OrderListView extends StandardListView<Order> {
}
