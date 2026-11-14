package info.ibtaura.auraclarus.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.SouthEast
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The Material icon drawn for an SF Symbol name, so screens can keep the iOS names (`auraIcon("figure.walk")`).
 * Covers every symbol the iOS app uses (`App/` and the shelf icons for modes.json). Unknown names get a plain circle.
 */
fun auraIcon(sfSymbolName: String): ImageVector = symbols[sfSymbolName] ?: Icons.Outlined.Circle

private val symbols: Map<String, ImageVector> = mapOf(
    // Tabs
    "heart.fill" to Icons.Filled.Favorite,
    "leaf.fill" to Icons.Filled.Eco,
    "magnifyingglass" to Icons.Filled.Search,

    // Shelves and categories
    "timer" to Icons.Filled.Timer,
    "arrow.clockwise.heart.fill" to AuraCustomIcons.HeartArrowClockwiseFill,
    "brain" to AuraCustomIcons.Brain,
    "brain.head.profile" to AuraCustomIcons.BrainHeadProfile,
    "bed.double.fill" to Icons.Filled.Bed,
    "figure.walk" to Icons.AutoMirrored.Filled.DirectionsWalk,
    "figure.walk.motion" to Icons.Filled.Hiking,
    "figure.run" to Icons.AutoMirrored.Filled.DirectionsRun,
    "figure.outdoor.cycle" to Icons.AutoMirrored.Filled.DirectionsBike,
    "figure.strengthtraining.traditional" to Icons.Filled.FitnessCenter,
    "figure.pool.swim" to Icons.Filled.Pool,
    "figure.arms.open" to Icons.Filled.Accessibility,
    "target" to Icons.Filled.TrackChanges,
    "person.2.fill" to Icons.Filled.People,
    "person.fill" to Icons.Filled.Person,
    "fork.knife" to Icons.Filled.Restaurant,
    "snowflake" to Icons.Filled.AcUnit,
    "hourglass" to Icons.Filled.HourglassBottom,
    "lungs.fill" to Icons.Filled.Air,
    "wind" to Icons.Filled.Air,
    "drop.fill" to Icons.Filled.WaterDrop,
    "ear.fill" to Icons.Filled.Hearing,
    "cross.circle.fill" to Icons.Filled.LocalHospital,
    "flame.fill" to Icons.Filled.LocalFireDepartment,
    "checklist" to Icons.Filled.Checklist,
    "chart.line.uptrend.xyaxis" to Icons.AutoMirrored.Filled.TrendingUp,
    "square.grid.2x2.fill" to Icons.Filled.GridView,
    "heart.text.square.fill" to Icons.Filled.HealthAndSafety,
    "sun.max.fill" to Icons.Filled.WbSunny,
    "moon.fill" to Icons.Filled.Bedtime,
    "sparkles" to Icons.Filled.AutoAwesome,

    // Search places
    "square.and.pencil" to Icons.Filled.Edit,
    "clock.arrow.circlepath" to Icons.Filled.History,
    "bolt.circle.fill" to Icons.Filled.Bolt,
    "gearshape.fill" to Icons.Filled.Settings,
    "doc.text.fill" to Icons.Filled.Description,

    // Controls
    "chevron.right" to Icons.AutoMirrored.Filled.KeyboardArrowRight,
    "chevron.left" to Icons.AutoMirrored.Filled.ArrowBackIos,
    "chevron.down" to Icons.Filled.KeyboardArrowDown,
    "chevron.up" to Icons.Filled.KeyboardArrowUp,
    "chevron.up.chevron.down" to Icons.Filled.UnfoldMore,
    "arrow.right" to Icons.AutoMirrored.Filled.ArrowForward,
    "arrow.up.right" to Icons.Filled.NorthEast,
    "arrow.down.right" to Icons.Filled.SouthEast,
    "xmark" to Icons.Filled.Close,
    "plus" to Icons.Filled.Add,
    "checkmark" to Icons.Filled.Check,
    "checkmark.circle.fill" to Icons.Filled.CheckCircle,
    "minus.circle.fill" to Icons.Filled.RemoveCircle,
    "delete.left" to Icons.AutoMirrored.Filled.Backspace,
    "trash" to Icons.Filled.Delete,
    "trash.fill" to Icons.Filled.Delete,
    "pencil" to Icons.Filled.Edit,
    "ellipsis" to Icons.Filled.MoreHoriz,
    "line.3.horizontal.decrease" to Icons.Filled.FilterList,
    "slider.horizontal.3" to Icons.Filled.Tune,
    "mic.fill" to Icons.Filled.Mic,
    "play.fill" to Icons.Filled.PlayArrow,
    "pause.fill" to Icons.Filled.Pause,
    "stop.fill" to Icons.Filled.Stop,
    "pin" to Icons.Outlined.PushPin,
    "pin.fill" to Icons.Filled.PushPin,
    "circle" to Icons.Outlined.Circle,
    "star.fill" to Icons.Filled.Star,
    "list.bullet" to Icons.AutoMirrored.Filled.List,
    "calendar" to Icons.Filled.CalendarMonth,
    "clock.fill" to Icons.Filled.WatchLater,
    "bell.fill" to Icons.Filled.Notifications,
    "phone" to Icons.Filled.Phone,
    "paintbrush" to Icons.Filled.Brush,

    // Status
    "info.circle" to Icons.Outlined.Info,
    "info.circle.fill" to Icons.Filled.Info,
    "exclamationmark.triangle" to Icons.Outlined.WarningAmber,
    "exclamationmark.triangle.fill" to Icons.Filled.Warning,
    "exclamationmark.circle.fill" to Icons.Filled.Error,

    // The mood slider's ends
    "tortoise.fill" to Icons.AutoMirrored.Filled.DirectionsWalk,
    "hare.fill" to Icons.AutoMirrored.Filled.DirectionsRun,
)
