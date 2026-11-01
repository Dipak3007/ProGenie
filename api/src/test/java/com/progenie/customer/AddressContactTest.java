package com.progenie.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.progenie.customer.AddressDtos.AddressRequest;
import com.progenie.shared.error.ApiException;
import org.junit.jupiter.api.Test;

/** Phase 3.5A: "Who will be there?" on an address is both name and phone, or neither. */
class AddressContactTest {

    private static AddressRequest req(String name, String phone) {
        return new AddressRequest("Home", "1 Main St", null, null, null, "380015", 23.03, 72.52, false, name, phone);
    }

    @Test
    void bothOrNeither() {
        assertThat(AddressService.contact(req(null, null))).isEqualTo(new AddressService.Contact(null, null));
        assertThat(AddressService.contact(req("  ", ""))).isEqualTo(new AddressService.Contact(null, null));
        assertThatThrownBy(() -> AddressService.contact(req("Mom", null)))
            .isInstanceOf(ApiException.class).hasMessageContaining("both");
        assertThatThrownBy(() -> AddressService.contact(req(null, "9876543210"))).isInstanceOf(ApiException.class);
    }

    @Test
    void thePhoneIsStoredAsTenDigits() {
        assertThat(AddressService.contact(req(" Asha Shah ", "+91 98765 43210")))
            .isEqualTo(new AddressService.Contact("Asha Shah", "9876543210"));
        assertThatThrownBy(() -> AddressService.contact(req("Asha", "12345")))
            .isInstanceOf(ApiException.class).hasMessageContaining("mobile");
        assertThatThrownBy(() -> AddressService.contact(req("Asha", "1234567890"))).isInstanceOf(ApiException.class);
    }
}
