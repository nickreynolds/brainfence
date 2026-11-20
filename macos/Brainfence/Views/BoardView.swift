import BrainfenceCore
import SwiftUI

/// A hangboard (shared/boards.json) with a step's holds highlighted and marked
/// L / R. With `onPick`, clicking a hold picks it (the step editor).
/// shared/CONTRACTS.md (Board holds).
struct BoardView: View {
    let board: BoardLayout
    let leftHold: String?
    let rightHold: String?
    var maxWidth: CGFloat = 560
    var onPick: ((String) -> Void)?

    // The board is a picture of wood, so it keeps its own colors in both themes.
    private static let ink = Color(red: 0x1B / 255, green: 0x1B / 255, blue: 0x21 / 255)
    private static let lime = Color(red: 0xD7 / 255, green: 0xFF / 255, blue: 0x1F / 255)
    private static let wood = Color(red: 0xE8 / 255, green: 0xCD / 255, blue: 0xA2 / 255)
    private static let woodEdge = Color(red: 0xC9 / 255, green: 0xA8 / 255, blue: 0x77 / 255)
    private static let woodTop = Color(red: 0xF1 / 255, green: 0xDD / 255, blue: 0xBB / 255)
    private static let sloperEdge = Color(red: 0xD9 / 255, green: 0xBC / 255, blue: 0x8E / 255)
    private static let hole = Color(red: 0xB4 / 255, green: 0x87 / 255, blue: 0x5A / 255)
    private static let divider = Color(red: 0xD2 / 255, green: 0xB2 / 255, blue: 0x85 / 255)

    /// Slopers first: the top pockets sit on the raised centre block.
    private var drawOrder: [BoardHold] {
        board.holds.filter { $0.kind == "sloper" } + board.holds.filter { $0.kind != "sloper" }
    }

    var body: some View {
        GeometryReader { geo in
            let s = geo.size.width / board.width
            Canvas { ctx, _ in
                func rect(_ x: Double, _ y: Double, _ w: Double, _ h: Double, _ r: Double) -> Path {
                    Path(roundedRect: CGRect(x: x * s, y: y * s, width: w * s, height: h * s), cornerRadius: r * s)
                }
                let body = board.body
                let bodyPath = rect(body.x, body.y, body.w, body.h, body.r)
                ctx.fill(bodyPath, with: .color(Self.wood))
                ctx.stroke(bodyPath, with: .color(Self.woodEdge), lineWidth: 4 * s)
                var line = Path()
                line.move(to: CGPoint(x: (body.x + 10) * s, y: board.dividerY * s))
                line.addLine(to: CGPoint(x: (body.x + body.w - 10) * s, y: board.dividerY * s))
                ctx.stroke(line, with: .color(Self.divider), lineWidth: 4 * s)
                for h in drawOrder {
                    let on = h.id == leftHold || h.id == rightHold
                    let sloper = h.kind == "sloper"
                    let p = rect(h.x, h.y, h.w, h.h, h.r)
                    ctx.fill(p, with: .color(on ? Self.lime : sloper ? Self.woodTop : Self.hole))
                    if on {
                        ctx.stroke(p, with: .color(Self.ink), lineWidth: 8 * s)
                    } else if sloper {
                        ctx.stroke(p, with: .color(Self.sloperEdge), lineWidth: 3 * s)
                    }
                }
                let left = board.holds.first { $0.id == leftHold }
                let right = board.holds.first { $0.id == rightHold }
                func marker(_ h: BoardHold, _ label: String) {
                    let cx = h.x + h.w / 2
                    // Above the hold: over the board for slopers, in the gap above a row otherwise.
                    let cy = h.kind == "sloper" ? board.body.y - 30 : h.y - 34
                    let half: Double = label.count > 1 ? 46 : 30
                    ctx.fill(rect(cx - half, cy - 30, half * 2, 60, 30), with: .color(Self.ink))
                    ctx.draw(
                        Text(label).font(.system(size: 36 * s, weight: .bold)).foregroundColor(Self.lime),
                        at: CGPoint(x: cx * s, y: cy * s)
                    )
                }
                if let left, left.id == right?.id {
                    marker(left, "LR")
                } else {
                    if let left { marker(left, "L") }
                    if let right { marker(right, "R") }
                }
            }
            .contentShape(Rectangle())
            .gesture(SpatialTapGesture().onEnded { tap in
                guard let onPick else { return }
                let x = tap.location.x / s
                let y = tap.location.y / s
                // Topmost first, with a little slop for small holds.
                if let hit = drawOrder.reversed().first(where: { h in
                    (h.x - 12...h.x + h.w + 12).contains(x) && (h.y - 12...h.y + h.h + 12).contains(y)
                }) {
                    onPick(hit.id)
                }
            }, including: onPick == nil ? .none : .all)
        }
        .aspectRatio(board.width / board.height, contentMode: .fit)
        .frame(maxWidth: maxWidth)
        .accessibilityLabel(board.name)
    }
}

/// The holds to use (drawn board) or the step's picture.
struct StepVisual: View {
    let step: RoutineStep
    var maxHeight: CGFloat = 220

    var body: some View {
        let config = step.stepType == "timed_sets" ? step.timedSets : nil
        if let config, let board = config.boardLayout {
            VStack(spacing: 4) {
                BoardView(board: board, leftHold: config.leftHold, rightHold: config.rightHold,
                          maxWidth: maxHeight * board.width / board.height)
                if let caption = config.holdsCaption {
                    Text(caption).font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
                }
            }
            .frame(maxWidth: .infinity)
        } else if let url = step.imageUrl {
            StepImage(url: url, maxHeight: maxHeight)
        }
    }
}
