package com.example.bankapp.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.bankapp.dto.TransferRequest;
import com.example.bankapp.exception.AccountNotFoundException;
import com.example.bankapp.exception.InsufficientBalanceException;
import com.example.bankapp.exception.InvalidTransferException;
import com.example.bankapp.model.BankTransaction;
import com.example.bankapp.model.Customer;
import com.example.bankapp.model.TransactionStatus;
import com.example.bankapp.repository.BankTransactionRepository;
import com.example.bankapp.repository.CustomerRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true) // Class-level: default for all methods is read-only transaction
public class BankService {

    public static final String FEE_ACCOUNT = "999999";
    public static final String CASH_ACCOUNT = "CASH";

    private final CustomerRepository customerRepository;
    private final BankTransactionRepository bankTransactionRepository;

    @Value("${bank.fee.enabled:false}")
    private boolean feeEnabled;

    // -------------------------------------------------------------
    // Read-only Queries
    // -------------------------------------------------------------

    public List<Customer> getAllCustomers() {
        return customerRepository.findAllOrdered();
    }

    public List<Customer> searchCustomers(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return customerRepository.findAllOrdered();
        }
        return customerRepository.searchByName(keyword.trim());
    }

    public Customer getCustomerByAccountNumber(String accountNumber) {
        return customerRepository.getByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));
    }

    public List<BankTransaction> getLatestTransactions() {
        return bankTransactionRepository.findAllLatest();
    }

    public List<BankTransaction> getAccountHistory(String accountNumber) {
        // Verify account exists
        getCustomerByAccountNumber(accountNumber);
        return bankTransactionRepository.findHistory(accountNumber);
    }

    public BigDecimal getTotalBalance() {
        return customerRepository.getTotalBalance();
    }

    public long countCustomers() {
        return customerRepository.countCustomersNative();
    }

    public long countSuccessTransactions() {
        return bankTransactionRepository.countByStatus(TransactionStatus.SUCCESS);
    }

    public long countFailedTransactions() {
        return bankTransactionRepository.countByStatus(TransactionStatus.FAILED);
    }

    public List<BankTransaction> getLargeTransfers(BigDecimal minAmount) {
        BigDecimal min = (minAmount != null) ? minAmount : new BigDecimal("10000.00");
        return bankTransactionRepository.findTransfersAboveAmount(min);
    }

    public BigDecimal getTotalSuccessfulAmountSent(String accountNumber) {
        return bankTransactionRepository.getTotalSuccessfulAmountSent(accountNumber);
    }

    // -------------------------------------------------------------
    // Write Operations (Read-Write Transactions)
    // -------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    //  @Transactional(propagation = Propagation.SUPPORTS)
    public Customer createCustomer(Customer customer) {
        if (customer.getAccountNumber() == null || customer.getAccountNumber().trim().isEmpty()) {
            throw new InvalidTransferException("Account number cannot be empty");
        }
        if (customerRepository.countAccounts(customer.getAccountNumber().trim()) > 0) {
            throw new InvalidTransferException("Account number " + customer.getAccountNumber() + " already exists");
        }
        if (customer.getEmail() == null || customer.getEmail().trim().isEmpty()) {
            throw new InvalidTransferException("Email cannot be empty");
        }
        if (customerRepository.countEmails(customer.getEmail().trim()) > 0) {
            throw new InvalidTransferException("Email address " + customer.getEmail() + " is already in use");
        }
        if (customer.getBalance() == null) {
            customer.setBalance(BigDecimal.ZERO);
        }
        return customerRepository.save(customer);
    }

    /**
     * Core transfer method.
     * Transactional with rollbackFor = Exception.class.
     * Exceptions are not caught here so Spring rollback triggers automatically.
     */
    @Transactional(rollbackFor = Exception.class)
    public BankTransaction transfer(TransferRequest request) {
        if (feeEnabled) {
            return transferWithFeeInternal(request);
        }
        return transferWithoutFeeInternal(request);
    }

    /**
     * Core standard transfer (no fee) expected by Tests 1-7.
     */
    @Transactional(rollbackFor = Exception.class)
    public BankTransaction transferWithoutFeeInternal(TransferRequest request) {
        String fromAccount = request.getFromAccount();
        String toAccount = request.getToAccount();
        BigDecimal amount = request.getAmount();

        // 1. Validate that amount is positive
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidTransferException("Transfer amount must be greater than zero");
        }

        // Block direct transfers involving fee account in standard transfer
        if (FEE_ACCOUNT.equals(fromAccount) || FEE_ACCOUNT.equals(toAccount)) {
            throw new InvalidTransferException("Direct transfers with the fee account " + FEE_ACCOUNT + " are not permitted");
        }

        // 2. Validate that sender and receiver are different
        if (fromAccount != null && fromAccount.trim().equalsIgnoreCase(toAccount != null ? toAccount.trim() : "")) {
            throw new InvalidTransferException("Sender and receiver accounts must be different");
        }

        // 3. Confirm that both accounts exist
        customerRepository.getByAccountNumber(fromAccount)
                .orElseThrow(() -> new AccountNotFoundException(fromAccount));
        customerRepository.getByAccountNumber(toAccount)
                .orElseThrow(() -> new AccountNotFoundException(toAccount));

        // 4. Call the atomic debit query
        int debitRows = customerRepository.debit(fromAccount, amount);

        // 5. If debit returns zero, throw InsufficientBalanceException
        if (debitRows == 0) {
            throw new InsufficientBalanceException(fromAccount);
        }

        // 6. If simulateFailure is true, throw immediately after debit and before credit
        if (request.isSimulateFailure()) {
            throw new RuntimeException("Simulated system failure after debiting sender");
        }

        // 7. Credit the receiver
        int creditRows = customerRepository.credit(toAccount, amount);

        // 8. If credit returns zero, throw AccountNotFoundException
        if (creditRows == 0) {
            throw new AccountNotFoundException(toAccount);
        }

        // 9. Save a SUCCESS BankTransaction
        BankTransaction transaction = BankTransaction.builder()
                .fromAccount(fromAccount)
                .toAccount(toAccount)
                .amount(amount)
                .status(TransactionStatus.SUCCESS)
                .description("Transfer from " + fromAccount + " to " + toAccount)
                .createdAt(LocalDateTime.now())
                .build();

        // 10. Return the saved transaction
        return bankTransactionRepository.save(transaction);
    }

    /**
     * Transfer with 1% fee extension (Task 12 Extension).
     * Calculates fee = amount * 0.01, rounded HALF_UP.
     * Debits sender (amount + fee).
     * Credits receiver (amount).
     * Credits fee account 999999 (fee).
     * If any part fails, all 3 roll back atomically.
     */
    @Transactional(rollbackFor = Exception.class)
    public BankTransaction transferWithFee(TransferRequest request) {
        return transferWithFeeInternal(request);
    }

    private BankTransaction transferWithFeeInternal(TransferRequest request) {
        String fromAccount = request.getFromAccount();
        String toAccount = request.getToAccount();
        BigDecimal amount = request.getAmount();

        // 1. Validate that amount is positive
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidTransferException("Transfer amount must be greater than zero");
        }

        if (FEE_ACCOUNT.equals(fromAccount) || FEE_ACCOUNT.equals(toAccount)) {
            throw new InvalidTransferException("Direct transfers involving fee account " + FEE_ACCOUNT + " are not permitted");
        }

        // 2. Validate sender and receiver are different
        if (fromAccount != null && fromAccount.trim().equalsIgnoreCase(toAccount != null ? toAccount.trim() : "")) {
            throw new InvalidTransferException("Sender and receiver accounts must be different");
        }

        // 3. Confirm both accounts exist
        customerRepository.getByAccountNumber(fromAccount)
                .orElseThrow(() -> new AccountNotFoundException(fromAccount));
        customerRepository.getByAccountNumber(toAccount)
                .orElseThrow(() -> new AccountNotFoundException(toAccount));

        // Ensure fee account exists with zero initial balance if missing
        ensureFeeAccountExists();

        // Calculate 1% fee rounded HALF_UP
        BigDecimal fee = amount.multiply(new BigDecimal("0.01")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalDebit = amount.add(fee);

        // 4. Debit sender by (amount + fee) atomically
        int debitRows = customerRepository.debit(fromAccount, totalDebit);
        if (debitRows == 0) {
            throw new InsufficientBalanceException(fromAccount);
        }

        // 5. Simulated failure check
        if (request.isSimulateFailure()) {
            throw new RuntimeException("Simulated system failure after debiting sender with fee");
        }

        // 6. Credit receiver with original amount
        int creditRows = customerRepository.credit(toAccount, amount);
        if (creditRows == 0) {
            throw new AccountNotFoundException(toAccount);
        }

        // 7. Credit fee account with fee
        if (fee.compareTo(BigDecimal.ZERO) > 0) {
            int feeRows = customerRepository.credit(FEE_ACCOUNT, fee);
            if (feeRows == 0) {
                throw new AccountNotFoundException(FEE_ACCOUNT);
            }
        }

        // 8. Save SUCCESS BankTransaction with fee details in description
        BankTransaction transaction = BankTransaction.builder()
                .fromAccount(fromAccount)
                .toAccount(toAccount)
                .amount(amount)
                .status(TransactionStatus.SUCCESS)
                .description(String.format("Transfer from %s to %s (Fee: LKR %.2f debited)", fromAccount, toAccount, fee))
                .createdAt(LocalDateTime.now())
                .build();

        return bankTransactionRepository.save(transaction);
    }

    public void ensureFeeAccountExists() {
        if (customerRepository.countAccounts(FEE_ACCOUNT) == 0) {
            Customer feeCustomer = Customer.builder()
                    .accountNumber(FEE_ACCOUNT)
                    .fullName("Bank Fee Reserve")
                    .email("fee.reserve@bankapp.example.com")
                    .balance(BigDecimal.ZERO)
                    .build();
            customerRepository.save(feeCustomer);
        }
    }

    // -------------------------------------------------------------
    // Deposit & Withdrawal Operations
    // -------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public BankTransaction deposit(String accountNumber, BigDecimal amount) {
        if (accountNumber == null || accountNumber.trim().isEmpty()) {
            throw new InvalidTransferException("Account number cannot be empty");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidTransferException("Deposit amount must be greater than zero");
        }
        customerRepository.getByAccountNumber(accountNumber.trim())
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));

        customerRepository.credit(accountNumber.trim(), amount);

        BankTransaction tx = BankTransaction.builder()
                .fromAccount(CASH_ACCOUNT)
                .toAccount(accountNumber.trim())
                .amount(amount)
                .status(TransactionStatus.SUCCESS)
                .description("Cash deposit to " + accountNumber)
                .createdAt(LocalDateTime.now())
                .build();

        return bankTransactionRepository.save(tx);
    }

    @Transactional(rollbackFor = Exception.class)
    public BankTransaction withdraw(String accountNumber, BigDecimal amount) {
        if (accountNumber == null || accountNumber.trim().isEmpty()) {
            throw new InvalidTransferException("Account number cannot be empty");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidTransferException("Withdrawal amount must be greater than zero");
        }
        customerRepository.getByAccountNumber(accountNumber.trim())
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));

        int updated = customerRepository.debit(accountNumber.trim(), amount);
        if (updated == 0) {
            throw new InsufficientBalanceException(accountNumber);
        }

        BankTransaction tx = BankTransaction.builder()
                .fromAccount(accountNumber.trim())
                .toAccount(CASH_ACCOUNT)
                .amount(amount)
                .status(TransactionStatus.SUCCESS)
                .description("Cash withdrawal from " + accountNumber)
                .createdAt(LocalDateTime.now())
                .build();

        return bankTransactionRepository.save(tx);
    }
}
