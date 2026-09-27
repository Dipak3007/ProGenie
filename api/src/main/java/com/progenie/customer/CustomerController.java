package com.progenie.customer;

import java.util.List;
import java.util.UUID;

import com.progenie.customer.AddressDtos.AddressDto;
import com.progenie.customer.AddressDtos.AddressRequest;
import com.progenie.provider.GenieDtos.GenieCardDto;
import com.progenie.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Customer-only: address book and favourite Genies. */
@RestController
@RequestMapping("/api/v1/me")
public class CustomerController {

    private final AddressService addresses;
    private final FavouriteService favourites;

    public CustomerController(AddressService addresses, FavouriteService favourites) {
        this.addresses = addresses;
        this.favourites = favourites;
    }

    @GetMapping("/addresses")
    public List<AddressDto> addresses() {
        return addresses.list(CurrentUser.id());
    }

    @PostMapping("/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    public AddressDto createAddress(@Valid @RequestBody AddressRequest req) {
        return addresses.create(CurrentUser.id(), req);
    }

    @PutMapping("/addresses/{id}")
    public AddressDto updateAddress(@PathVariable UUID id, @Valid @RequestBody AddressRequest req) {
        return addresses.update(CurrentUser.id(), id, req);
    }

    @PostMapping("/addresses/{id}/default")
    public AddressDto makeDefault(@PathVariable UUID id) {
        return addresses.makeDefault(CurrentUser.id(), id);
    }

    @DeleteMapping("/addresses/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAddress(@PathVariable UUID id) {
        addresses.delete(CurrentUser.id(), id);
    }

    @GetMapping("/favourites")
    public List<GenieCardDto> favourites() {
        return favourites.list(CurrentUser.id());
    }

    @PutMapping("/favourites/{genieId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addFavourite(@PathVariable UUID genieId) {
        favourites.add(CurrentUser.id(), genieId);
    }

    @DeleteMapping("/favourites/{genieId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFavourite(@PathVariable UUID genieId) {
        favourites.remove(CurrentUser.id(), genieId);
    }
}
