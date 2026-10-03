package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.Goal;
import com.picsou.model.SharedResource;
import com.picsou.model.SharingLevel;
import com.picsou.model.SharingSettings;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.GoalManualContributionRepository;
import com.picsou.repository.GoalRepository;
import com.picsou.repository.SharedResourceRepository;
import com.picsou.repository.SharingSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FamilyViewServiceTest {

    @Mock FamilyMemberRepository memberRepository;
    @Mock SharingSettingsRepository sharingSettingsRepository;
    @Mock SharedResourceRepository sharedResourceRepository;
    @Mock AccountRepository accountRepository;
    @Mock GoalRepository goalRepository;
    @Mock AccountService accountService;
    @Mock GoalManualContributionRepository contributionRepository;

    @InjectMocks FamilyViewService familyViewService;

    @Test
    void getFamilyDashboard_manualSharingReadsResourcesScopedToOwner() {
        FamilyMember viewer = FamilyMember.builder().id(1L).displayName("Viewer").build();
        FamilyMember owner = FamilyMember.builder().id(2L).displayName("Owner").build();
        Account account = Account.builder()
            .id(10L)
            .member(owner)
            .name("Savings")
            .type(AccountType.SAVINGS)
            .currency("EUR")
            .currentBalance(new BigDecimal("1000"))
            .build();
        Goal goal = Goal.builder()
            .id(20L)
            .member(owner)
            .name("House")
            .targetAmount(new BigDecimal("50000"))
            .deadline(LocalDate.now().plusYears(1))
            .accounts(List.of())
            .build();

        when(memberRepository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(viewer, owner));
        when(sharingSettingsRepository.findByMemberIdAndResourceType(2L, "ACCOUNT"))
            .thenReturn(Optional.of(new SharingSettings(1L, owner, "ACCOUNT", SharingLevel.MANUAL)));
        when(sharingSettingsRepository.findByMemberIdAndResourceType(2L, "GOAL"))
            .thenReturn(Optional.of(new SharingSettings(2L, owner, "GOAL", SharingLevel.MANUAL)));
        when(sharedResourceRepository.findAllByOwnerMemberIdAndResourceType(2L, "ACCOUNT"))
            .thenReturn(List.of(SharedResource.builder().resourceId(10L).build()));
        when(sharedResourceRepository.findAllByOwnerMemberIdAndResourceType(2L, "GOAL"))
            .thenReturn(List.of(SharedResource.builder().resourceId(20L).build()));
        when(accountRepository.findAllByIdInAndMemberId(List.of(10L), 2L)).thenReturn(List.of(account));
        when(goalRepository.findAllByIdInAndMemberId(List.of(20L), 2L)).thenReturn(List.of(goal));
        when(accountService.liveBalanceEur(account)).thenReturn(new BigDecimal("1000"));

        var dashboard = familyViewService.getFamilyDashboard(1L);

        assertThat(dashboard.sharedAccounts().stream().map(a -> a.id()).toList()).containsExactly(10L);
        assertThat(dashboard.sharedGoals().stream().map(g -> g.id()).toList()).containsExactly(20L);
        verify(accountRepository).findAllByIdInAndMemberId(List.of(10L), 2L);
        verify(goalRepository).findAllByIdInAndMemberId(List.of(20L), 2L);
    }

    @Test
    void getFamilyDashboard_ignoresContaminatedGoalAccountsOutsideOwner() {
        FamilyMember viewer = FamilyMember.builder().id(1L).displayName("Viewer").build();
        FamilyMember owner = FamilyMember.builder().id(2L).displayName("Owner").build();
        Account ownedAccount = Account.builder()
            .id(10L)
            .member(owner)
            .name("Savings")
            .type(AccountType.SAVINGS)
            .currency("EUR")
            .currentBalance(new BigDecimal("1000"))
            .build();
        Account foreignAccount = Account.builder()
            .id(99L)
            .name("Foreign")
            .type(AccountType.SAVINGS)
            .currency("EUR")
            .currentBalance(new BigDecimal("999999"))
            .build();
        Goal goal = Goal.builder()
            .id(20L)
            .member(owner)
            .name("House")
            .targetAmount(new BigDecimal("50000"))
            .deadline(LocalDate.now().plusYears(1))
            .accounts(List.of(ownedAccount, foreignAccount))
            .build();

        when(memberRepository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(viewer, owner));
        when(sharingSettingsRepository.findByMemberIdAndResourceType(2L, "ACCOUNT"))
            .thenReturn(Optional.empty());
        when(sharingSettingsRepository.findByMemberIdAndResourceType(2L, "GOAL"))
            .thenReturn(Optional.of(new SharingSettings(2L, owner, "GOAL", SharingLevel.ALL)));
        when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(2L)).thenReturn(List.of(goal));
        when(accountRepository.findAllByIdInAndMemberId(List.of(10L, 99L), 2L)).thenReturn(List.of(ownedAccount));
        when(accountService.liveBalanceEur(ownedAccount)).thenReturn(new BigDecimal("1000"));

        var dashboard = familyViewService.getFamilyDashboard(1L);

        assertThat(dashboard.sharedGoals()).hasSize(1);
        assertThat(dashboard.sharedGoals().getFirst().currentTotal()).isEqualByComparingTo("1000");
        verify(accountService, never()).liveBalanceEur(foreignAccount);
    }

    @Test
    void getFamilyDashboard_manualSharingWithEmptySharedIds_returnsEmptyListWithoutQueryingRepos() {
        FamilyMember viewer = FamilyMember.builder().id(1L).displayName("Viewer").build();
        FamilyMember owner = FamilyMember.builder().id(2L).displayName("Owner").build();

        when(memberRepository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(viewer, owner));
        when(sharingSettingsRepository.findByMemberIdAndResourceType(2L, "ACCOUNT"))
            .thenReturn(Optional.of(new SharingSettings(1L, owner, "ACCOUNT", SharingLevel.MANUAL)));
        when(sharingSettingsRepository.findByMemberIdAndResourceType(2L, "GOAL"))
            .thenReturn(Optional.of(new SharingSettings(2L, owner, "GOAL", SharingLevel.MANUAL)));
        when(sharedResourceRepository.findAllByOwnerMemberIdAndResourceType(2L, "ACCOUNT"))
            .thenReturn(List.of());
        when(sharedResourceRepository.findAllByOwnerMemberIdAndResourceType(2L, "GOAL"))
            .thenReturn(List.of());

        var dashboard = familyViewService.getFamilyDashboard(1L);

        assertThat(dashboard.sharedAccounts()).isEmpty();
        assertThat(dashboard.sharedGoals()).isEmpty();
        verify(accountRepository, never()).findAllByIdInAndMemberId(any(), any());
        verify(goalRepository, never()).findAllByIdInAndMemberId(any(), any());
    }
}
