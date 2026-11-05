package org.financelab.budget.verification;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;

@Route("other")
public class OtherView extends Div {
    public OtherView() { add(new RouterLink("Назад", TestView.class)); }
}
