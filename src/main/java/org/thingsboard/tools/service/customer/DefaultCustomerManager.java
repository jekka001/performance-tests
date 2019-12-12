package org.thingsboard.tools.service.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thingsboard.client.tools.RestClient;
import org.thingsboard.server.common.data.Customer;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.group.EntityGroupInfo;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.permission.GroupPermission;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.tools.service.dashboard.DashboardManager;
import org.thingsboard.tools.service.shared.DefaultRestClientService;
import org.thingsboard.tools.service.shared.RestClientService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class DefaultCustomerManager implements CustomerManager {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final List<CustomerId> customerIds = Collections.synchronizedList(new ArrayList<>(1024));

    @Value("${customer.startIdx}")
    int customerStartIdx;
    @Value("${customer.endIdx}")
    int customerEndIdx;

    @Autowired
    private DashboardManager dashboardManager;
    @Autowired
    private RestClientService restClientService;

    @Override
    public List<CustomerId> getCustomerIds() {
        return customerIds;
    }

    @Override
    public void createCustomers() throws Exception {
        int customerCount = customerEndIdx - customerStartIdx;
        if (customerCount == 0) {
            return;
        }

        log.info("Creating {} customers...", customerCount);
        CountDownLatch latch = new CountDownLatch(customerCount);
        AtomicInteger count = new AtomicInteger();
        for (int i = customerStartIdx; i < customerEndIdx; i++) {
            final int tokenNumber = i;
            restClientService.getHttpExecutor().submit(() -> {
                Customer customer = null;
                try {
                    String title = "C" + String.format("%8d", tokenNumber).replace(" ", "0");
                    customer = getRestClient().createCustomer(title);
                    //TODO: REST client improvement
                    List<EntityGroupInfo> groups = getRestClient().getEntityGroupsByOwnerAndType(
                            customer.getId().getEntityType().name(), customer.getId().getId().toString(), EntityType.USER);
                    EntityGroupInfo customerAdmins = groups.stream().filter(g -> g.getName().equalsIgnoreCase("Customer Administrators")).findFirst().orElseThrow(() -> new RuntimeException("Customer Administrator Group is not present!"));

                    GroupPermission readOnlyPermission = new GroupPermission();
                    readOnlyPermission.setEntityGroupId(dashboardManager.getSharedDashboardsGroup().getId());
                    readOnlyPermission.setEntityGroupType(dashboardManager.getSharedDashboardsGroup().getType());
                    readOnlyPermission.setPublic(false);
                    readOnlyPermission.setRoleId(dashboardManager.getReadOnlyRole().getId());
                    readOnlyPermission.setTenantId(customer.getTenantId());
                    readOnlyPermission.setUserGroupId(customerAdmins.getId());
                    getRestClient().saveGroupPermission(readOnlyPermission);

                    User customerAdmin = new User();
                    customerAdmin.setFirstName("User " + tokenNumber);
                    customerAdmin.setEmail("user@customer" + tokenNumber + ".com");
                    customerAdmin.setAuthority(Authority.CUSTOMER_USER);
                    customerAdmin.setTenantId(customer.getTenantId());
                    customerAdmin.setCustomerId(customer.getId());
                    ObjectNode dashboardInfo = mapper.createObjectNode();
                    dashboardInfo.put("defaultDashboardFullscreen", false);
                    dashboardInfo.put("defaultDashboardId", dashboardManager.getSharedDashboardId().getId().toString());
                    customerAdmin.setAdditionalInfo(dashboardInfo);

                    customerAdmin = getRestClient().saveUser(customerAdmin, false);
                    //TODO: REST CLIENT
                    getRestClient().addEntitiesToEntityGroup(customerAdmins.getId().toString(), new String[]{customerAdmin.getId().toString()});
                    getRestClient().activateUser(customerAdmin.getId().toString(), "customer");
                    customerIds.add(customer.getId());
                    count.getAndIncrement();
                } catch (Exception e) {
                    log.error("Error while creating entity", e);
                    if (customer != null && customer.getId() != null) {
                        getRestClient().deleteCustomer(customer.getId());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        ScheduledFuture<?> logScheduleFuture = restClientService.getLogScheduler().scheduleAtFixedRate(() -> {
            try {
                log.info("{} customers have been created so far...", count.get());
            } catch (Exception ignored) {
            }
        }, 0, DefaultRestClientService.LOG_PAUSE, TimeUnit.SECONDS);

        latch.await();
        logScheduleFuture.cancel(true);
        log.info("{} customers have been created successfully!", customerIds.size());
    }

    @Override
    public void removeCustomers() throws Exception {
        if (customerIds.size() == 0) {
            return;
        }
        log.info("Removing {} customers", customerIds.size());
        CountDownLatch latch = new CountDownLatch(customerIds.size());
        AtomicInteger count = new AtomicInteger();
        for (CustomerId entityId : customerIds) {
            restClientService.getHttpExecutor().submit(() -> {
                try {
                    restClientService.getRestClient().deleteCustomer(entityId);
                    count.getAndIncrement();
                } catch (Exception e) {
                    log.error("Error while deleting customer", e);
                } finally {
                    latch.countDown();
                }
            });
        }

        ScheduledFuture<?> logScheduleFuture = restClientService.getLogScheduler().scheduleAtFixedRate(() -> {
            try {
                log.info("{} customers have been removed so far...", count.get());
            } catch (Exception ignored) {
            }
        }, 0, DefaultRestClientService.LOG_PAUSE, TimeUnit.SECONDS);

        latch.await();
        logScheduleFuture.cancel(true);
        Thread.sleep(1000);
        log.info("{} customers have been removed successfully! {} were failed for removal!", count.get(), customerIds.size() - count.get());
    }

    private RestClient getRestClient() {
        return restClientService.getRestClient();
    }
}
