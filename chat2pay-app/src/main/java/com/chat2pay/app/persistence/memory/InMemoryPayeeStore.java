package com.chat2pay.app.persistence.memory;

import com.chat2pay.app.api.dto.ChatDtos.PayeeSummary;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Repository
public class InMemoryPayeeStore {

    public record RegisteredPayee(PayeeSummary summary, List<String> aliases) {}

    private static PayeeSummary payee(String id, String name, String type, String bankCode,
                                      String bankName, String account, String label) {
        return new PayeeSummary(id, name, type, bankCode, bankName, account, label);
    }

    private final List<RegisteredPayee> payees = List.of(
            new RegisteredPayee(
                    payee("payee_bob_current", "Bob Chan", "DOMESTIC_REGISTERED", "004",
                            "HSBC Hong Kong", "123-456-789", "CURRENT • 123-456-789"),
                    List.of("bob chan", "bob")),
            new RegisteredPayee(
                    payee("payee_bob_savings", "Bob Chan", "DOMESTIC_REGISTERED", "004",
                            "HSBC Hong Kong", "987-654-321", "SAVINGS • 987-654-321"),
                    List.of("bob chan", "bob")),
            new RegisteredPayee(
                    payee("payee_sarah_salary", "Sarah Wong", "DOMESTIC_REGISTERED", "012",
                            "Bank of China (Hong Kong)", "800-221-456", "PAYROLL • 800-221-456"),
                    List.of("sarah wong", "sarah")),
            new RegisteredPayee(
                    payee("payee_alex_ops", "Alex Tan", "DOMESTIC_REGISTERED", "024",
                            "Hang Seng Bank", "556-000-912", "OPERATIONS • 556-000-912"),
                    List.of("alex tan", "alex")),
            new RegisteredPayee(
                    payee("payee_michelle_vendor", "Michelle Ng", "DOMESTIC_REGISTERED", "005",
                            "Citibank Hong Kong", "445-221-007", "VENDOR • 445-221-007"),
                    List.of("michelle ng", "michelle"))
    );

    public List<RegisteredPayee> all() {
        return payees;
    }

    public Optional<RegisteredPayee> findById(String payeeId) {
        return payees.stream().filter(p -> p.summary().payeeId().equals(payeeId)).findFirst();
    }

    public List<RegisteredPayee> findByQuery(String query) {
        if (query == null || query.isBlank()) return List.of();
        String normalized = query.toLowerCase(Locale.ROOT).trim();
        return payees.stream()
                .filter(p -> p.summary().name().toLowerCase(Locale.ROOT).contains(normalized)
                        || p.aliases().stream().anyMatch(a ->
                                a.contains(normalized) || normalized.contains(a)))
                .toList();
    }

    public String findAliasInText(String text) {
        if (text == null) return null;
        String normalized = text.toLowerCase(Locale.ROOT);
        return payees.stream()
                .flatMap(p -> p.aliases().stream())
                .distinct()
                .sorted((a, b) -> b.length() - a.length())
                .filter(normalized::contains)
                .findFirst()
                .orElse(null);
    }

    public List<String> knownAliases() {
        return payees.stream().flatMap(p -> p.aliases().stream()).distinct().toList();
    }

    @SuppressWarnings("unused")
    private static List<String> splitAliases(String csv) {
        return Arrays.asList(csv.split(","));
    }
}
