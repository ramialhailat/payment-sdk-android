package payment.sdk.android.demo.ui.screen.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import payment.sdk.android.core.SavedCard
import java.util.Locale

@Composable
fun HomeBottomBar(
    modifier: Modifier,
    total: Double,
    isSamsungPayAvailable: Boolean,
    savedCard: SavedCard?,
    savedCards: List<SavedCard>,
    onClickPayByCard: () -> Unit,
    onClickSamsungPay: () -> Unit,
    onSelectCard: (SavedCard) -> Unit,
    onDeleteSavedCard: (SavedCard) -> Unit,
    onPaySavedCard: (SavedCard) -> Unit
) {
    var expandSavedCards by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        shadowElevation = 8.dp,
        tonalElevation = 8.dp,
        modifier = modifier
    ) {
        Column {
            if (!expandSavedCards) {
                savedCard?.let {
                    SavedCardView(it, onPay = { onPaySavedCard(it) })
                }
            }

            AnimatedVisibility(
                visible = expandSavedCards,
                enter = expandVertically(expandFrom = Alignment.Top),
                exit = shrinkVertically(shrinkTowards = Alignment.Top)
            ) {
                Column {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        text = "Select Saved Card",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium
                    )

                    HorizontalDivider()
                    LazyColumn {
                        items(savedCards) { card ->
                            SavedCardView(card, savedCard == card, true, onClick = {
                                onSelectCard(card)
                                expandSavedCards = !expandSavedCards
                            }, onDelete = {
                                onDeleteSavedCard(card)
                            })
                        }
                    }
                }
            }

            if (savedCards.size > 1 && !expandSavedCards) {
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clickable { expandSavedCards = !expandSavedCards }) {
                    Text(
                        modifier = Modifier.align(Alignment.Center),
                        text = "Show more cards",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            AnimatedVisibility(
                visible = !expandSavedCards,
                enter = expandVertically(expandFrom = Alignment.Top),
                exit = shrinkVertically(shrinkTowards = Alignment.Top)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(modifier = Modifier.weight(1f), onClick = onClickPayByCard) {
                        Text(text = "Pay AED ${"%.2f".format(Locale.ENGLISH, total)}")
                    }
                    if (isSamsungPayAvailable) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = onClickSamsungPay
                        ) {
                            Text(text = "Samsung Pay")
                        }
                    }
                }
            }
        }
    }
}