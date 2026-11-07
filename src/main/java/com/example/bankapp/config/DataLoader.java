package com.example.bankapp.config;

import com.example.bankapp.model.Customer;
import com.example.bankapp.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Inserts the three required sample customers the first time the application starts
 * with an empty database. Starting balance total: LKR 85,000.00.
 */
@Component
@RequiredArgsConstructor
public class DataLoader implements CommandLineRunner {

    private final CustomerRepository customerRepository;

    @Override
    public void run(String... args) {
        if (customerRepository.count() == 0) {
            Customer c1 = Customer.builder()
                    .accountNumber("100001")
                    .fullName("Kamal Silva")
                    .email("kamal.silva@example.com")
                    .balance(new BigDecimal("50000.00"))
                    .build();

            Customer c2 = Customer.builder()
                    .accountNumber("100002")
                    .fullName("Nimali Perera")
                    .email("nimali.perera@example.com")
                    .balance(new BigDecimal("25000.00"))
                    .build();

            Customer c3 = Customer.builder()
                    .accountNumber("100003")
                    .fullName("Saman Fernando")
                    .email("saman.fernando@example.com")
                    .balance(new BigDecimal("10000.00"))
                    .build();

            customerRepository.saveAll(List.of(c1, c2, c3));
        }
    }
}
