package com.vn.jmixapp.view.orderline;

import com.vn.jmixapp.entity.OrderLine;
import com.vn.jmixapp.view.main.MainView;
import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.*;

@Route(value = "orderLines/:id", layout = MainView.class)
@ViewController(id = "OrderLine.detail")
@ViewDescriptor(path = "order-line-detail-view.xml")
@EditedEntityContainer("orderLineDc")
@DialogMode(width = "32em")
@PrimaryDetailView(OrderLine.class)
public class OrderLineDetailView extends StandardDetailView<OrderLine> {
}
