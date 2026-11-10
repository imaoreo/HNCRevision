import RevisionCore
import SwiftUI

/// Streaks, the last 14 days of activity, and progress per unit.
struct ProgressScreen: View {
    @Environment(RevisionStore.self) private var store
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                streaks
                chart
                if let latest = store.mockHistory.first {
                    latestMock(latest)
                }
                Text("By unit")
                    .appFont(.title3, weight: .semibold)
                    .accessibilityAddTraits(.isHeader)
                ForEach(store.unitProgress) { progress in
                    unitCard(progress)
                }
                Text("Progress is saved on this device only. Quiz figures use your latest Practice answers; mock exams are listed separately.")
                    .appFont(.footnote)
                    .foregroundStyle(Palette.muted)
            }
            .foregroundStyle(Palette.ink)
            .padding(16)
            .readableWidth()
        }
        .background(PaperBackground())
        .navigationTitle("Progress")
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder private var streaks: some View {
        let layout = dynamicTypeSize.isAccessibilitySize ? AnyLayout(VStackLayout(spacing: 12)) : AnyLayout(HStackLayout(spacing: 12))
        layout {
            tile(value: store.currentStreak, label: "day streak", icon: "flame.fill")
            tile(value: store.bestStreak, label: "best streak", icon: "trophy.fill")
            tile(value: store.totalDueCount, label: "cards due", icon: "calendar")
        }
    }

    private func tile(value: Int, label: String, icon: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Image(systemName: icon)
                .foregroundStyle(Palette.progressFill)
                .accessibilityHidden(true)
            Text("\(value)")
                .appFont(.title, weight: .bold)
                .monospacedDigit()
            Text(label)
                .appFont(.caption)
                .foregroundStyle(Palette.muted)
        }
        .cardStyle(radius: 16, padding: 14)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(value) \(label)")
    }

    private var chart: some View {
        let days = store.recentActivity(days: 14)
        let peak = max(days.map(\.count).max() ?? 0, 1)
        return VStack(alignment: .leading, spacing: 10) {
            Text("Last 14 days")
                .appFont(.headline)
                .accessibilityAddTraits(.isHeader)
            HStack(alignment: .bottom, spacing: 4) {
                ForEach(days, id: \.day) { entry in
                    let isToday = entry.day == store.today
                    VStack(spacing: 4) {
                        if entry.count > 0 {
                            Text("\(entry.count)")
                                .font(.caption2)
                                .monospacedDigit()
                                .foregroundStyle(Palette.muted)
                                .lineLimit(1)
                                .minimumScaleFactor(0.6)
                        }
                        RoundedRectangle(cornerRadius: 3)
                            .fill(entry.count > 0 ? Palette.progressFill : Palette.progressTrack)
                            .frame(height: entry.count > 0 ? max(6, 110 * CGFloat(entry.count) / CGFloat(peak)) : 3)
                        Text(entry.day.startDate(in: .current).formatted(.dateTime.weekday(.narrow)))
                            .font(.caption2.weight(isToday ? .bold : .regular))
                            .foregroundStyle(isToday ? Palette.ink : Palette.muted)
                    }
                    .frame(maxWidth: .infinity, alignment: .bottom)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(entry.day.startDate(in: .current).formatted(.dateTime.weekday(.wide).day().month(.wide)))
                    .accessibilityValue(entry.count == 1 ? "1 action" : "\(entry.count) actions")
                }
            }
            .frame(height: 150, alignment: .bottom)
            Text("Cards rated, Practice answers checked and mock questions answered each day.")
                .appFont(.caption)
                .foregroundStyle(Palette.muted)
        }
        .cardStyle(padding: 16)
    }

    private func latestMock(_ record: MockRecord) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Latest mock exam").appFont(.headline).accessibilityAddTraits(.isHeader)
            Text("\(shortDate(record.date)) · \(record.correct)/\(record.total) · \(record.percent)%")
                .appFont(.body)
                .monospacedDigit()
        }
        .cardStyle(radius: 16, padding: 14)
        .accessibilityElement(children: .combine)
    }

    private func unitCard(_ p: RevisionStore.UnitProgress) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("\(p.unit.code) · \(p.unit.title)")
                .appFont(.headline)
                .accessibilityAddTraits(.isHeader)
            ProgressBar(value: p.cards > 0 ? Double(p.confident) / Double(p.cards) : 0)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text("Confident \(p.confident) / \(p.cards) cards")
                Text(p.due == 1 ? "1 due" : "\(p.due) due")
                Text(p.quizAnswered > 0 ? "Quiz: \(p.quizCorrect) / \(p.quizAnswered) correct (\(p.quizPercent)%)" : "Quiz: not started")
                if let mock = p.latestMock {
                    Text("Latest mock: \(mock.correct) / \(mock.asked) (\(mock.percent)%)")
                }
            }
            .appFont(.subheadline)
            .foregroundStyle(Palette.ink)
        }
        .cardStyle(radius: 16, padding: 16)
        .accessibilityElement(children: .combine)
    }
}
