package pl.detailing.crm.finance.payment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Plan grupowej zmiany statusu płatności. Reguły są krótkie, ale każda z nich wzięła
 * się z konkretnego ryzyka: zaznaczenie „wszystko" obejmuje też dokumenty już opłacone,
 * a pojedyncza pozycja, której nie wolno ruszyć, nie może przewrócić całej operacji.
 */
class BulkPaymentStatusPlanTest {

    private fun item(key: String, status: String, paidIsFinal: Boolean = false) =
        BulkPaymentStatusPlan.Item(key, status, paidIsFinal)

    @Test
    @DisplayName("dokumenty o innym statusie idą do zmiany")
    fun `zmienia to co ma inny status`() {
        val plan = BulkPaymentStatusPlan.of(
            requested = listOf("a", "b"),
            found     = listOf(item("a", "PENDING"), item("b", "PENDING")),
            target    = "PAID"
        )

        assertThat(plan.toChange).containsExactly("a", "b")
        assertThat(plan.unchanged).isEmpty()
        assertThat(plan.skipped).isEmpty()
    }

    @Test
    @DisplayName("dokument mający już docelowy status to nie błąd, tylko brak zmiany")
    fun `juz w docelowym statusie trafia do unchanged`() {
        val plan = BulkPaymentStatusPlan.of(
            requested = listOf("a", "b"),
            found     = listOf(item("a", "PAID"), item("b", "PENDING")),
            target    = "PAID"
        )

        assertThat(plan.toChange).containsExactly("b")
        assertThat(plan.unchanged).containsExactly("a")
        assertThat(plan.skipped).isEmpty()
    }

    @Test
    @DisplayName("opłaconego dokumentu modułu finansowego nie da się cofnąć, reszta przechodzi")
    fun `paid jest koncowy dla dokumentow finansowych`() {
        val plan = BulkPaymentStatusPlan.of(
            requested = listOf("finance-paid", "ksef-paid"),
            found     = listOf(
                item("finance-paid", "PAID", paidIsFinal = true),
                item("ksef-paid", "PAID")
            ),
            target    = "PENDING"
        )

        assertThat(plan.toChange).containsExactly("ksef-paid")
        assertThat(plan.skipped)
            .containsExactly(BulkPaymentStatusPlan.Skipped("finance-paid", BulkPaymentStatusPlan.REASON_PAID_IS_FINAL))
    }

    @Test
    @DisplayName("dokument spoza studia wraca jako pominięty, a nie jako błąd całej operacji")
    fun `nieznane id nie wywraca operacji`() {
        val plan = BulkPaymentStatusPlan.of(
            requested = listOf("a", "obcy"),
            found     = listOf(item("a", "PENDING")),
            target    = "PAID"
        )

        assertThat(plan.toChange).containsExactly("a")
        assertThat(plan.skipped)
            .containsExactly(BulkPaymentStatusPlan.Skipped("obcy", BulkPaymentStatusPlan.REASON_NOT_FOUND))
    }

    @Test
    @DisplayName("powtórzone id liczy się raz")
    fun `duplikaty sa zwijane`() {
        val plan = BulkPaymentStatusPlan.of(
            requested = listOf("a", "a", "a"),
            found     = listOf(item("a", "PENDING")),
            target    = "PAID"
        )

        assertThat(plan.toChange).containsExactly("a")
    }

    @Test
    @DisplayName("przeterminowany wraca do oczekujących - zaległość to nadal brak zapłaty")
    fun `overdue mozna cofnac do pending`() {
        val plan = BulkPaymentStatusPlan.of(
            requested = listOf("a"),
            found     = listOf(item("a", "OVERDUE", paidIsFinal = true)),
            target    = "PENDING"
        )

        assertThat(plan.toChange).containsExactly("a")
    }

    @Test
    @DisplayName("status spoza PAID/PENDING nie jest obsługiwany")
    fun `nieobslugiwany status docelowy`() {
        val result = runCatching {
            BulkPaymentStatusPlan.of(requested = listOf("a"), found = emptyList(), target = "OVERDUE")
        }

        assertThat(result.isFailure).isTrue()
    }
}
