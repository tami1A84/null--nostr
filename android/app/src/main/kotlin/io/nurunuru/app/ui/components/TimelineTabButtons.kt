package io.nurunuru.app.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors

@Composable
fun TimelineTabButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val nuruColors = LocalNuruColors.current
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) LineGreen else Color.Transparent,
            contentColor = if (selected) Color.White else nuruColors.textTertiary
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        modifier = Modifier.height(32.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = null
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
