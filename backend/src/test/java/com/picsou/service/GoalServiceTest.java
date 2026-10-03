package com.picsou.service;

import com.picsou.dto.GoalProgressResponse;
import com.picsou.dto.GoalRequest;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.Goal;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.GoalManualContributionRepository;
import com.picsou.repository.GoalMonthOverrideRepository;
import com.picsou.repository.GoalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoalServiceTest {

    @Mock GoalRepository goalRepository;
    @Mock AccountRepository accountRepository;
    @Mock BalanceSnapshotRepository snapshotRepository;
    @Mock AccountService accountService;
    @Mock GoalMonthOverrideRepository overrideRepository;
    @Mock GoalManualContributionRepository manualContributionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock HistoryService historyService;

    @InjectMocks GoalService goalService;

    @Test
    void create_rejectsAccountIdsOutsideCurrentMember() {
        FamilyMember member = FamilyMember.builder().id(1L).displayName("Alice").build();
        Account ownedAccount = Account.builder().id(10L).build();
        GoalRequest request = new GoalRequest(
            "Emergency fund",
            new BigDecimal("10000"),
            LocalDate.now().plusMonths(6),
            List.of(10L, 20L)
        );
        when(accountRepository.findAllByIdInAndMemberId(List.of(10L, 20L), 1L))
            .thenReturn(List.of(ownedAccount));

        assertThatThrownBy(() -> goalService.create(request, member))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("account IDs not found");

        verify(goalRepository, never()).save(any(Goal.class));
    }

    @Test
    void update_rejectsAccountIdsOutsideCurrentMember() {
        Goal goal = Goal.builder().id(1L).accounts(List.of()).build();
        GoalRequest request = new GoalRequest(
            "Emergency fund",
            new BigDecimal("10000"),
            LocalDate.now().plusMonths(6),
            List.of(10L, 20L)
        );
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(java.util.Optional.of(goal));
        when(accountRepository.findAllByIdInAndMemberId(List.of(10L, 20L), 1L))
            .thenReturn(List.of(Account.builder().id(10L).build()));

        assertThatThrownBy(() -> goalService.update(1L, request, 1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("account IDs not found");

        verify(goalRepository, never()).save(any(Goal.class));
    }

    @Test
    void progressCalculation_ignoresContaminatedAccountsOutsideGoalMember() {
        FamilyMember member = FamilyMember.builder().id(1L).displayName("Alice").build();
        Account ownedAccount = Account.builder()
            .id(10L)
            .member(member)
            .name("LEP")
            .type(AccountType.LEP)
            .currency("EUR")
            .currentBalance(new BigDecimal("5000"))
            .color("#6366f1")
            .build();
        Account foreignAccount = Account.builder()
            .id(20L)
            .name("Foreign")
            .type(AccountType.SAVINGS)
            .currency("EUR")
            .currentBalance(new BigDecimal("999999"))
            .build();
        Goal goal = Goal.builder()
            .id(1L)
            .member(member)
            .name("Emergency fund")
            .targetAmount(new BigDecimal("10000"))
            .deadline(LocalDate.now().plusMonths(6))
            .accounts(List.of(ownedAccount, foreignAccount))
            .build();

        when(accountRepository.findAllByIdInAndMemberId(List.of(10L, 20L), 1L))
            .thenReturn(List.of(ownedAccount));
        when(accountService.toResponse(ownedAccount)).thenReturn(
            new com.picsou.dto.AccountResponse(
                10L, "LEP", AccountType.LEP, null, "EUR",
                new BigDecimal("5000"), new BigDecimal("5000"),
                null, true, "#6366f1", null, null, null, null
            )
        );
        when(accountService.liveBalanceEur(ownedAccount)).thenReturn(new BigDecimal("5000"));
        when(snapshotRepository.findRecentByAccountId(
            org.mockito.ArgumentMatchers.eq(10L),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of());

        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        assertThat(progress.currentTotal()).isEqualByComparingTo("5000");
        assertThat(progress.accounts().stream().map(com.picsou.dto.AccountResponse::id).toList())
            .containsExactly(10L);
        verify(accountService, never()).liveBalanceEur(foreignAccount);
        verify(accountService, never()).toResponse(foreignAccount);
    }

    @Test
    void deleteMonthOverride_rejectsGoalOutsideMember() {
        when(goalRepository.findByIdAndMemberId(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> goalService.deleteMonthOverride(99L, "2026-06", 1L))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(overrideRepository, never()).delete(any());
    }

    @Test
    void deleteManualContribution_rejectsGoalOutsideMember() {
        when(goalRepository.findByIdAndMemberId(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> goalService.deleteManualContribution(99L, "2026-06", 1L))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(manualContributionRepository, never()).delete(any());
    }

    @Test
    void create_handlesDuplicateAccountIds() {
        FamilyMember member = FamilyMember.builder().id(1L).displayName("Alice").build();
        Account ownedAccount = Account.builder().id(10L).member(member).build();
        GoalRequest request = new GoalRequest(
            "Emergency fund",
            new BigDecimal("10000"),
            LocalDate.now().plusMonths(6),
            List.of(10L, 10L)
        );
        when(accountRepository.findAllByIdInAndMemberId(List.of(10L), 1L))
            .thenReturn(List.of(ownedAccount));
        when(goalRepository.save(any(Goal.class))).thenAnswer(inv -> {
            Goal g = inv.getArgument(0);
            g.setId(1L);
            return g;
        });

        GoalProgressResponse response = goalService.create(request, member);
        assertThat(response).isNotNull();
        verify(goalRepository).save(any(Goal.class));
    }

    @Test
    void progressCalculation_onTrack() {
        Account account = Account.builder()
            .id(1L)
            .name("LEP")
            .type(AccountType.LEP)
            .currency("EUR")
            .currentBalance(new BigDecimal("5000"))
            .color("#6366f1")
            .build();

        Goal goal = Goal.builder()
            .id(1L)
            .name("Apport immobilier")
            .targetAmount(new BigDecimal("20000"))
            .deadline(LocalDate.now().plusMonths(6))
            .accounts(List.of(account))
            .build();

        when(accountService.toResponse(account)).thenReturn(
            new com.picsou.dto.AccountResponse(
                1L, "LEP", AccountType.LEP, null, "EUR",
                new BigDecimal("5000"), new BigDecimal("5000"),
                null, true, "#6366f1", null, null, null, null
            )
        );
        when(accountService.liveBalanceEur(account)).thenReturn(new BigDecimal("5000"));
        when(snapshotRepository.findRecentByAccountId(
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of());

        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        assertThat(progress.currentTotal()).isEqualByComparingTo("5000");
        assertThat(progress.targetAmount()).isEqualByComparingTo("20000");

        // The deadline is `now + 6 months`, but ChronoUnit.MONTHS.between clamps to 5
        // when today's day-of-month doesn't exist in the target month (e.g. running on
        // the 31st → November has 30 days). Derive the expectation from the actual
        // monthsLeft so the test verifies the *relationship* (monthlyNeeded = needed /
        // monthsLeft) and stays green on every calendar day.
        long monthsLeft = progress.monthsLeft();
        assertThat(monthsLeft).isIn(5L, 6L);
        assertThat(progress.monthlyNeeded()).isEqualByComparingTo(
            new BigDecimal("15000").divide(BigDecimal.valueOf(monthsLeft), 2, RoundingMode.HALF_UP));
        assertThat(progress.percentComplete()).isEqualByComparingTo("25.0000");
    }

    @Test
    void isOnTrack_false_whenPastEffectivesBelowPastObjectives() {
        // 3 past months, each with snapshot delta = 1000€.
        // Target 12000, current 0, deadline +3 months → monthlyNeeded = 4000.
        // sumObjectivePast = 3 * 4000 = 12000 ; sumEffectivePast = 3 * 1000 = 3000 → behind.
        Account account = Account.builder()
            .id(1L).name("Livret").type(AccountType.SAVINGS)
            .currency("EUR").currentBalance(BigDecimal.ZERO)
            .color("#000").build();

        java.time.Instant created = LocalDate.now().minusMonths(3).withDayOfMonth(1)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
        Goal goal = Goal.builder()
            .id(1L).name("Test").targetAmount(new BigDecimal("12000"))
            .deadline(LocalDate.now().plusMonths(3))
            .accounts(List.of(account))
            .build();
        org.springframework.test.util.ReflectionTestUtils.setField(goal, "createdAt", created);

        when(accountService.toResponse(account)).thenReturn(
            new com.picsou.dto.AccountResponse(
                1L, "Livret", AccountType.SAVINGS, null, "EUR",
                BigDecimal.ZERO, BigDecimal.ZERO,
                null, true, "#000", null, null, null, null
            )
        );
        when(accountService.liveBalanceEur(account)).thenReturn(BigDecimal.ZERO);
        when(snapshotRepository.findRecentByAccountId(
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of());

        // Per-month-end balances so each past month delta = 1000.
        // M-4 end = 0, M-3 end = 1000, M-2 end = 2000, M-1 end = 3000.
        java.time.YearMonth now = java.time.YearMonth.now();
        for (int i = 1; i <= 3; i++) {
            java.time.YearMonth past = now.minusMonths(i);
            LocalDate prevEnd = past.minusMonths(1).atEndOfMonth();
            LocalDate thisEnd = past.atEndOfMonth();
            BigDecimal prevBalance = new BigDecimal(String.valueOf((4 - i - 1) * 1000));
            BigDecimal thisBalance = new BigDecimal(String.valueOf((4 - i) * 1000));
            lenient().when(snapshotRepository
                .findFirstByAccountIdAndDateLessThanEqualOrderByDateDesc(1L, prevEnd))
                .thenReturn(java.util.Optional.of(
                    com.picsou.model.BalanceSnapshot.builder()
                        .balance(prevBalance).date(prevEnd).build()));
            lenient().when(snapshotRepository
                .findFirstByAccountIdAndDateLessThanEqualOrderByDateDesc(1L, thisEnd))
                .thenReturn(java.util.Optional.of(
                    com.picsou.model.BalanceSnapshot.builder()
                        .balance(thisBalance).date(thisEnd).build()));
        }
        lenient().when(overrideRepository.findByGoalId(1L)).thenReturn(List.of());
        lenient().when(manualContributionRepository.findByGoalId(1L)).thenReturn(List.of());

        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        assertThat(progress.isOnTrack()).isFalse();
    }

    @Test
    void isOnTrack_true_whenManualContributionCoversShortfall() {
        // Same setup as the "behind" test but user declares 4000€ manual contribution
        // for each of the 3 past months → effective matches objective → on track.
        Account account = Account.builder()
            .id(1L).name("Livret").type(AccountType.SAVINGS)
            .currency("EUR").currentBalance(BigDecimal.ZERO)
            .color("#000").build();

        java.time.Instant created = LocalDate.now().minusMonths(3).withDayOfMonth(1)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
        Goal goal = Goal.builder()
            .id(1L).name("Test").targetAmount(new BigDecimal("12000"))
            .deadline(LocalDate.now().plusMonths(3))
            .accounts(List.of(account))
            .build();
        org.springframework.test.util.ReflectionTestUtils.setField(goal, "createdAt", created);

        when(accountService.toResponse(account)).thenReturn(
            new com.picsou.dto.AccountResponse(
                1L, "Livret", AccountType.SAVINGS, null, "EUR",
                BigDecimal.ZERO, BigDecimal.ZERO,
                null, true, "#000", null, null, null, null
            )
        );
        when(accountService.liveBalanceEur(account)).thenReturn(BigDecimal.ZERO);
        when(snapshotRepository.findRecentByAccountId(
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of());

        when(overrideRepository.findByGoalId(1L)).thenReturn(List.of());

        java.time.YearMonth now = java.time.YearMonth.now();
        java.util.List<com.picsou.model.GoalManualContribution> manuals = new java.util.ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            com.picsou.model.GoalManualContribution m = new com.picsou.model.GoalManualContribution();
            m.setGoal(goal);
            m.setYearMonth(now.minusMonths(i).toString());
            m.setAmount(new BigDecimal("4000"));
            manuals.add(m);
        }
        when(manualContributionRepository.findByGoalId(1L)).thenReturn(manuals);

        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        assertThat(progress.isOnTrack()).isTrue();
    }

    @Test
    void isOnTrack_true_whenNoPastMonthHasData() {
        // Goal created this month → no past months → benefit of the doubt.
        Account account = Account.builder()
            .id(1L).name("LEP").type(AccountType.LEP)
            .currency("EUR").currentBalance(BigDecimal.ZERO)
            .color("#000").build();

        Goal goal = Goal.builder()
            .id(1L).name("Tout neuf").targetAmount(new BigDecimal("10000"))
            .deadline(LocalDate.now().plusMonths(6))
            .accounts(List.of(account))
            .build();
        org.springframework.test.util.ReflectionTestUtils.setField(goal, "createdAt", java.time.Instant.now());

        when(accountService.toResponse(account)).thenReturn(
            new com.picsou.dto.AccountResponse(
                1L, "LEP", AccountType.LEP, null, "EUR",
                BigDecimal.ZERO, BigDecimal.ZERO,
                null, true, "#000", null, null, null, null
            )
        );
        when(accountService.liveBalanceEur(account)).thenReturn(BigDecimal.ZERO);
        when(snapshotRepository.findRecentByAccountId(
            org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of());

        GoalProgressResponse progress = goalService.toProgressResponse(goal);

        assertThat(progress.isOnTrack()).isTrue();
    }

    // ─── Backfill history ───────────────────────────────────────────────────

    private Goal backfillGoal(String historyStartMonth) {
        java.time.Instant created = LocalDate.now().withDayOfMonth(1)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
        Goal goal = Goal.builder()
            .id(1L).name("Backfill").targetAmount(new BigDecimal("1000"))
            .deadline(LocalDate.now().plusMonths(2))
            .accounts(List.of())
            .historyStartMonth(historyStartMonth)
            .build();
        org.springframework.test.util.ReflectionTestUtils.setField(goal, "createdAt", created);
        return goal;
    }

    @Test
    void getMonthlyEntries_usesHistoryStartWhenEarlierThanCreatedAt() {
        String historyStart = java.time.YearMonth.now().minusYears(1).toString();
        Goal goal = backfillGoal(historyStart);
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(java.util.Optional.of(goal));

        var entries = goalService.getMonthlyEntries(1L, 1L);

        assertThat(entries).isNotEmpty();
        assertThat(entries.get(0).yearMonth()).isEqualTo(historyStart);
        // No accounts and no manual/override data → backfilled month is empty.
        assertThat(entries.get(0).effective()).isNull();
    }

    @Test
    void getMonthlyEntries_ignoresHistoryStartWhenLaterThanCreatedAt() {
        String historyStart = java.time.YearMonth.now().plusMonths(2).toString();
        Goal goal = backfillGoal(historyStart);
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(java.util.Optional.of(goal));

        var entries = goalService.getMonthlyEntries(1L, 1L);

        assertThat(entries.get(0).yearMonth()).isEqualTo(java.time.YearMonth.now().toString());
    }

    @Test
    void extendHistory_decrementsByOneYearFromCreatedAt() {
        Goal goal = backfillGoal(null);
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(java.util.Optional.of(goal));
        when(goalRepository.save(goal)).thenReturn(goal);

        GoalProgressResponse progress = goalService.extendHistory(1L, 1L);

        String expected = java.time.YearMonth.now().minusYears(1).toString();
        assertThat(goal.getHistoryStartMonth()).isEqualTo(expected);
        assertThat(progress.historyStartMonth()).isEqualTo(expected);
    }

    @Test
    void extendHistory_decrementsFromExistingHistoryStart() {
        String existing = java.time.YearMonth.now().minusYears(1).toString();
        Goal goal = backfillGoal(existing);
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(java.util.Optional.of(goal));
        when(goalRepository.save(goal)).thenReturn(goal);

        goalService.extendHistory(1L, 1L);

        assertThat(goal.getHistoryStartMonth())
            .isEqualTo(java.time.YearMonth.now().minusYears(2).toString());
    }

    @Test
    void extendHistoryByMonth_decrementsByOneMonthFromCreatedAt() {
        Goal goal = backfillGoal(null);
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(java.util.Optional.of(goal));
        when(goalRepository.save(goal)).thenReturn(goal);

        GoalProgressResponse progress = goalService.extendHistoryByMonth(1L, 1L);

        String expected = java.time.YearMonth.now().minusMonths(1).toString();
        assertThat(goal.getHistoryStartMonth()).isEqualTo(expected);
        assertThat(progress.historyStartMonth()).isEqualTo(expected);
    }

    @Test
    void extendHistoryByMonth_decrementsFromExistingHistoryStart() {
        String existing = java.time.YearMonth.now().minusMonths(1).toString();
        Goal goal = backfillGoal(existing);
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(java.util.Optional.of(goal));
        when(goalRepository.save(goal)).thenReturn(goal);

        goalService.extendHistoryByMonth(1L, 1L);

        assertThat(goal.getHistoryStartMonth())
            .isEqualTo(java.time.YearMonth.now().minusMonths(2).toString());
    }

    @Test
    void isOnTrack_unaffectedByHistoryStart() {
        // History extended far back with no data → on-track stays anchored to createdAt
        // (created this month → no past months → benefit of the doubt).
        Goal goal = backfillGoal(java.time.YearMonth.now().minusYears(5).toString());
        when(goalRepository.findByIdAndMemberId(1L, 1L)).thenReturn(java.util.Optional.of(goal));

        GoalProgressResponse progress = goalService.findById(1L, 1L);

        assertThat(progress.isOnTrack()).isTrue();
    }
}
