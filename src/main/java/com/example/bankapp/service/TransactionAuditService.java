package com.example.bankapp.service;

import com.example.bankapp.model.BankTransaction;
import com.example.bankapp.model.TransactionStatus;
import com.example.bankapp.repository.BankTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Saves a FAILED record after a transfer was rolled back.
 * REQUIRES_NEW = always start a brand-new, independent transaction,
 * so this record is committed even though the transfer itself was rolled back.
 */
@Service
@RequiredArgsConstructor
public class TransactionAuditService {

    private final BankTransactionRepository bankTransactionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BankTransaction recordFailure(String fromAccount, String toAccount, BigDecimal amount, String failureMessage) {
        BankTransaction transaction = BankTransaction.builder()
                .fromAccount(fromAccount != null ? fromAccount : "UNKNOWN")
                .toAccount(toAccount != null ? toAccount : "UNKNOWN")
                .amount(amount != null ? amount : BigDecimal.ZERO)
                .status(TransactionStatus.FAILED)
                .description("Failed transfer: " + failureMessage)
                .createdAt(LocalDateTime.now())
                .build();

        return bankTransactionRepository.save(transaction);
    }
}
