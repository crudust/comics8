package com.comics8.monitor.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comics8.monitor.ui.ListingUiState
import com.comics8.monitor.ui.ListingViewModel

const val LOOSE_IMAGE_HINT =
    "ZIP/CBZ 파일이나 이미지가 들어 있는 하위 폴더를 넣어 주세요. 루트의 낱개 이미지 파일은 스캔하지 않습니다."

@Composable
fun EmptyLibraryPane(
    onAddFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Text(
            text = "만화 폴더를 추가하세요",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAddFolder) {
            Text("폴더 추가")
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = LOOSE_IMAGE_HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun EmptyLibraryScanHint(modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(24.dp),
    ) {
        Text(
            text = LOOSE_IMAGE_HINT,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun LibraryRootsBar(
    state: ListingUiState,
    viewModel: ListingViewModel,
    onAddFolder: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.libraryRoots.isEmpty()) {
                Text(
                    text = "폴더 없음",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.libraryRoots.forEach { uri ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = viewModel.libraryRootLabel(uri),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(
                            onClick = { viewModel.removeLibraryRoot(uri) },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "폴더 제거",
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
        TextButton(onClick = onAddFolder) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("폴더 추가")
        }
    }
}
