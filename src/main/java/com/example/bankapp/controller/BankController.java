package com.example.bankapp.controller;

import com.example.bankapp.dto.TransferRequest;
import com.example.bankapp.model.Customer;
import com.example.bankapp.service.BankService;
import com.example.bankapp.service.TransactionAuditService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

@Controller
@RequiredArgsConstructor
public class BankController {

    private final BankService bankService;
    private final TransactionAuditService transactionAuditService;

    @GetMapping("/")
    public String dashboard(@RequestParam(value = "keyword", required = false) String keyword, Model model) {
        model.addAttribute("keyword", keyword);
        model.addAttribute("customers", bankService.searchCustomers(keyword));
        model.addAttribute("allCustomers", bankService.getAllCustomers());
        model.addAttribute("totalBalance", bankService.getTotalBalance());
        model.addAttribute("totalCustomers", bankService.countCustomers());
        model.addAttribute("successCount", bankService.countSuccessTransactions());
        model.addAttribute("failedCount", bankService.countFailedTransactions());
        model.addAttribute("transactions", bankService.getLatestTransactions());

        if (!model.containsAttribute("transferRequest")) {
            model.addAttribute("transferRequest", new TransferRequest());
        }
        if (!model.containsAttribute("customer")) {
            model.addAttribute("customer", new Customer());
        }
        return "bank";
    }

    @PostMapping("/customers")
    public String addCustomer(@Valid @ModelAttribute("customer") Customer customer,
                              BindingResult result,
                              RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            redirectAttributes.addFlashAttribute("org.springframework.validation.BindingResult.customer", result);
            redirectAttributes.addFlashAttribute("customer", customer);
            String firstError = result.getAllErrors().get(0).getDefaultMessage();
            redirectAttributes.addFlashAttribute("error", "Validation error: " + firstError);
            return "redirect:/";
        }

        try {
            bankService.createCustomer(customer);
            redirectAttributes.addFlashAttribute("success", "Customer " + customer.getFullName() + " created successfully!");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("customer", customer);
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/transfer")
    public String handleTransfer(@Valid @ModelAttribute("transferRequest") TransferRequest request,
                                 BindingResult result,
                                 RedirectAttributes redirectAttributes) {
        // Do not record FAILED rows for bean validation errors that prevented the service call
        if (result.hasErrors()) {
            redirectAttributes.addFlashAttribute("org.springframework.validation.BindingResult.transferRequest", result);
            redirectAttributes.addFlashAttribute("transferRequest", request);
            String firstError = result.getAllErrors().get(0).getDefaultMessage();
            redirectAttributes.addFlashAttribute("error", "Validation error: " + firstError);
            return "redirect:/";
        }

        try {
            bankService.transfer(request);
            redirectAttributes.addFlashAttribute("success",
                    String.format("Transferred LKR %.2f successfully from %s to %s",
                            request.getAmount(), request.getFromAccount(), request.getToAccount()));
        } catch (Exception ex) {
            // Service call failed and rolled back; record independent audit log via REQUIRES_NEW
            transactionAuditService.recordFailure(
                    request.getFromAccount(),
                    request.getToAccount(),
                    request.getAmount(),
                    ex.getMessage()
            );
            redirectAttributes.addFlashAttribute("error", "Transfer failed: " + ex.getMessage());
        }
        return "redirect:/";
    }

    @GetMapping("/accounts/{accountNumber}/history")
    public String accountHistory(@PathVariable("accountNumber") String accountNumber,
                                 Model model,
                                 RedirectAttributes redirectAttributes) {
        try {
            model.addAttribute("customer", bankService.getCustomerByAccountNumber(accountNumber));
            model.addAttribute("transactions", bankService.getAccountHistory(accountNumber));
            model.addAttribute("totalSent", bankService.getTotalSuccessfulAmountSent(accountNumber));
            return "history";
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
            return "redirect:/";
        }
    }

    @GetMapping("/large-transfers")
    public String largeTransfers(@RequestParam(value = "min", defaultValue = "10000.00") BigDecimal min,
                                 Model model) {
        model.addAttribute("min", min);
        model.addAttribute("transfers", bankService.getLargeTransfers(min));
        return "large-transfers";
    }

    @GetMapping("/deposit")
    public String showDepositForm(Model model) {
        model.addAttribute("customers", bankService.getAllCustomers());
        return "deposit";
    }

    @PostMapping("/deposit")
    public String handleDeposit(@RequestParam("accountNumber") String accountNumber,
                                @RequestParam("amount") BigDecimal amount,
                                RedirectAttributes redirectAttributes) {
        try {
            bankService.deposit(accountNumber, amount);
            redirectAttributes.addFlashAttribute("success",
                    String.format("Deposited LKR %.2f to account %s successfully", amount, accountNumber));
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("error", "Deposit failed: " + ex.getMessage());
        }
        return "redirect:/deposit";
    }

    @GetMapping("/withdraw")
    public String showWithdrawForm(Model model) {
        model.addAttribute("customers", bankService.getAllCustomers());
        return "withdraw";
    }

    @PostMapping("/withdraw")
    public String handleWithdraw(@RequestParam("accountNumber") String accountNumber,
                                 @RequestParam("amount") BigDecimal amount,
                                 RedirectAttributes redirectAttributes) {
        try {
            bankService.withdraw(accountNumber, amount);
            redirectAttributes.addFlashAttribute("success",
                    String.format("Withdrew LKR %.2f from account %s successfully", amount, accountNumber));
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("error", "Withdrawal failed: " + ex.getMessage());
        }
        return "redirect:/withdraw";
    }
}
