package io.eia.b2bgateway.domain.validation

import io.eia.b2bgateway.domain.edifact.Segment
import io.eia.b2bgateway.domain.spec.StructureNode

/**
 * UNH と UNT の間のセグメントの並びと回数を、仕様の並び(structure)で検査する(スキーマの段階。ADR-0041 §4)。
 * 並びの順にセグメントを読み進める。グループはトリガーセグメントが来るたびに 1 回と数える。
 * - 必須のセグメント・グループがない → 13(位置は、そこで見つかったセグメント。末尾なら UNT)
 * - 回数の超過 → セグメントは 35、グループは 36(位置は、超えた回のセグメント)
 * - 修飾子ごとの必須と回数(必須の修飾子がない → 13、回数の超過 → 35 / 36)
 * - 並びのどこにも当てはまらないセグメント → 15(位置はそのセグメント。そこで検査をやめる)
 *
 * [body] の位置は、UNH を 1 として数える(本文の最初のセグメントが 2)。
 */
public object StructureCheck {
    private const val FIRST_BODY_POSITION = 2

    public fun check(
        structure: List<StructureNode>,
        body: List<Segment>,
    ): List<SegmentError> {
        val cursor = Cursor(body)
        val errors = mutableListOf<SegmentError>()
        match(structure, cursor, errors)
        if (!cursor.done) errors += SegmentError(cursor.position, SyntaxErrorCode.NOT_SUPPORTED_IN_POSITION)
        return errors
    }

    private class Cursor(
        val body: List<Segment>,
    ) {
        var index = 0
        val done: Boolean get() = index >= body.size
        val tag: String? get() = body.getOrNull(index)?.tag

        /** 今のセグメントの位置(UNH を 1 として数える。末尾なら UNT の位置)。 */
        val position: Int get() = index + FIRST_BODY_POSITION

        fun qualifier(): String = body[index].value(1)
    }

    private fun match(
        nodes: List<StructureNode>,
        cursor: Cursor,
        errors: MutableList<SegmentError>,
    ) {
        nodes.forEach { node ->
            when (node) {
                is StructureNode.SegmentNode -> matchSegment(node, cursor, errors)
                is StructureNode.GroupNode -> matchGroup(node, cursor, errors)
            }
        }
    }

    private fun matchSegment(
        node: StructureNode.SegmentNode,
        cursor: Cursor,
        errors: MutableList<SegmentError>,
    ) {
        val start = cursor.position
        val occurrences = mutableListOf<Pair<Int, String>>()
        while (cursor.tag == node.tag) {
            occurrences += cursor.position to cursor.qualifier()
            if (occurrences.size == node.max + 1) errors += SegmentError(cursor.position, SyntaxErrorCode.TOO_MANY_SEGMENT_REPETITIONS)
            cursor.index++
        }
        if (occurrences.isEmpty() && node.mandatory) errors += SegmentError(start, SyntaxErrorCode.MISSING)
        if (occurrences.isNotEmpty()) errors += checkQualifiers(node, occurrences, SyntaxErrorCode.TOO_MANY_SEGMENT_REPETITIONS)
    }

    private fun matchGroup(
        node: StructureNode.GroupNode,
        cursor: Cursor,
        errors: MutableList<SegmentError>,
    ) {
        val start = cursor.position
        val occurrences = mutableListOf<Pair<Int, String>>()
        // トリガーセグメントは 1 回ごとに 1 つだけ読み、残りの中身を並びで検査する(トリガーが続けば、次の回)
        while (cursor.tag == node.trigger) {
            occurrences += cursor.position to cursor.qualifier()
            if (occurrences.size == node.max + 1) errors += SegmentError(cursor.position, SyntaxErrorCode.TOO_MANY_GROUP_REPETITIONS)
            cursor.index++
            match(node.content.drop(1), cursor, errors)
        }
        if (occurrences.isEmpty() && node.mandatory) errors += SegmentError(start, SyntaxErrorCode.MISSING)
        if (occurrences.isNotEmpty()) errors += checkQualifiers(node, occurrences, SyntaxErrorCode.TOO_MANY_GROUP_REPETITIONS)
    }

    /** 修飾子ごとの必須と回数。[occurrences] は (位置, 修飾子) の並び。必須の修飾子がないときの位置は、最初の出現。 */
    private fun checkQualifiers(
        node: StructureNode,
        occurrences: List<Pair<Int, String>>,
        tooMany: SyntaxErrorCode,
    ): List<SegmentError> =
        node.qualifiers.flatMap { qualifier ->
            val positions = occurrences.filter { it.second == qualifier.value }.map { it.first }
            buildList {
                if (positions.isEmpty() && qualifier.mandatory) add(SegmentError(occurrences.first().first, SyntaxErrorCode.MISSING))
                positions.drop(qualifier.max).take(1).forEach { add(SegmentError(it, tooMany)) }
            }
        }
}
