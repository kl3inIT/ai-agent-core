package com.vn.jmixapp.view.customer;

import com.vn.jmixapp.entity.Customer;
import com.vn.jmixapp.view.main.MainView;
import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.*;

@Route(value = "customers", layout = MainView.class)
@ViewController(id = "Customer.list")
@ViewDescriptor(path = "customer-list-view.xml")
@LookupComponent("customersDataGrid")
@DialogMode(width = "64em")
@PrimaryListView(Customer.class)
public class CustomerListView extends StandardListView<Customer> {
}
