package com.example.albanianidverification.adapters

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.albanianidverification.R
import com.example.albanianidverification.api.models.PartyResponse
import com.google.android.material.card.MaterialCardView

/**
 * BallotAdapter
 *
 * Shows parties as expandable cards. Each party only shows the candidates the
 * voter is eligible to vote for, filtered using the set returned by
 * GET /api/v1/vote/candidates/{electionId}.
 *
 * The backend already applies the correct region rule:
 *   • LOCAL_GOVERNMENT  → candidates in the voter's municipality only
 *   • PARLIAMENTARY     → candidates in the voter's county only
 *
 * The adapter simply hides any candidate whose id is NOT in [eligibleCandidateIds].
 *
 * IMPORTANT — null vs empty:
 *   • eligibleCandidateIds = null   → eligible list never loaded, show ALL (fallback)
 *   • eligibleCandidateIds = empty  → server returned zero eligible candidates, show NONE
 *   This distinction prevents the old bug where an empty set let every candidate through.
 */
class BallotAdapter(
    private val parties: List<PartyResponse>,
    /** Null = not yet loaded (show all as fallback). Empty = voter has no eligible candidates. */
    private val eligibleCandidateIds: Set<String>?,
    private val onSelectionChanged: (
        partyId: String,
        candidateId: String,
        candidateName: String,
        partyName: String
    ) -> Unit
) : RecyclerView.Adapter<BallotAdapter.PartyVH>() {

    private var expandedPosition = RecyclerView.NO_ID.toInt()
    var selectedPartyId: String?     = null; private set
    var selectedCandidateId: String? = null; private set

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        PartyVH(LayoutInflater.from(parent.context).inflate(R.layout.item_party, parent, false))

    override fun onBindViewHolder(holder: PartyVH, position: Int) =
        holder.bind(parties[position], position)

    override fun getItemCount() = parties.size

    /**
     * Returns true if [candidateId] is eligible for this voter.
     *   • null set → show everything (data not yet available)
     *   • empty set → show nothing (voter has no eligible candidates)
     *   • populated set → show only matching ids
     */
    private fun isEligible(candidateId: String?): Boolean {
        if (candidateId == null) return false
        return eligibleCandidateIds == null || eligibleCandidateIds.contains(candidateId)
    }

    inner class PartyVH(v: View) : RecyclerView.ViewHolder(v) {
        private val card: MaterialCardView      = v.findViewById(R.id.partyCard)
        private val numberBadge: TextView       = v.findViewById(R.id.partyNumber)
        private val nameText: TextView          = v.findViewById(R.id.partyName)
        private val codeText: TextView          = v.findViewById(R.id.partyCode)
        private val leaderText: TextView        = v.findViewById(R.id.partyLeader)
        private val countText: TextView         = v.findViewById(R.id.candidateCount)
        private val expandIcon: TextView        = v.findViewById(R.id.expandIcon)
        private val selectedBar: View           = v.findViewById(R.id.selectedPartyIndicator)
        private val candidatesBox: LinearLayout = v.findViewById(R.id.candidatesContainer)

        fun bind(party: PartyResponse, pos: Int) {
            val expanded   = expandedPosition == pos
            val isSelected = selectedPartyId == party.id

            nameText.text    = party.name
            codeText.text    = party.partyCode ?: ""
            leaderText.text  = party.leader?.let { "Leader: $it" } ?: ""
            numberBadge.text = (party.listNumber ?: pos + 1).toString()

            // Count only the candidates the voter can actually vote for
            val eligible = party.candidates.orEmpty().filter { isEligible(it.id) }
            countText.text = when {
                eligible.isEmpty() -> "No candidates in your region"
                eligible.size == 1 -> "1 candidate"
                else               -> "${eligible.size} candidates"
            }

            applyPartyColor(party.color, isSelected)

            selectedBar.visibility   = if (isSelected) View.VISIBLE else View.GONE
            expandIcon.text          = if (expanded) "▲" else "▼"
            candidatesBox.visibility = if (expanded) View.VISIBLE else View.GONE
            if (expanded) buildCandidateRows(party, candidatesBox)

            card.setOnClickListener {
                val prev = expandedPosition
                expandedPosition = if (expanded) -1 else pos
                notifyItemChanged(pos)
                if (prev != -1 && prev != pos) notifyItemChanged(prev)
            }
        }

        private fun applyPartyColor(colorHex: String?, isSelected: Boolean) {
            val color = try {
                val hex = colorHex?.let { if (it.startsWith("#")) it else "#$it" } ?: "#CC0000"
                Color.parseColor(hex)
            } catch (e: Exception) {
                Color.parseColor("#CC0000")
            }
            numberBadge.setBackgroundColor(color)
            card.strokeColor = color
            card.strokeWidth = if (isSelected) 6 else 2
        }

        private fun buildCandidateRows(party: PartyResponse, box: LinearLayout) {
            box.removeAllViews()
            val ctx = itemView.context

            // Filter to the voter's eligible candidates; sort by list position
            val eligible = party.candidates.orEmpty()
                .filter { isEligible(it.id) }
                .sortedBy { it.positionInList ?: Int.MAX_VALUE }

            if (eligible.isEmpty()) {
                box.addView(TextView(ctx).apply {
                    text = "No candidates available for your region"
                    setPadding(48, 24, 48, 24)
                    setTextColor(Color.GRAY)
                })
                return
            }

            eligible.forEach { candidate ->
                val row = LayoutInflater.from(ctx)
                    .inflate(R.layout.item_candidate, box, false)

                val radio  = row.findViewById<RadioButton>(R.id.candidateRadio)
                val nameTv = row.findViewById<TextView>(R.id.candidateName)
                val posTv  = row.findViewById<TextView>(R.id.candidatePosition)
                val profTv = row.findViewById<TextView>(R.id.candidateProfession)
                val ageTv  = row.findViewById<TextView>(R.id.candidateAge)

                nameTv.text = candidate.displayName
                posTv.text  = candidate.positionInList?.let { "#$it on list" } ?: ""
                profTv.text = candidate.profession ?: ""
                ageTv.text  = candidate.age?.let { "Age $it" } ?: ""

                radio.setOnCheckedChangeListener(null)
                radio.isChecked = selectedCandidateId == candidate.id

                radio.setOnCheckedChangeListener { _, checked ->
                    if (!checked) return@setOnCheckedChangeListener
                    val prevPartyId = selectedPartyId
                    selectedPartyId     = party.id
                    selectedCandidateId = candidate.id

                    if (prevPartyId != null && prevPartyId != party.id) {
                        val prevIdx = parties.indexOfFirst { it.id == prevPartyId }
                        if (prevIdx != -1) notifyItemChanged(prevIdx)
                    }
                    buildCandidateRows(party, box)
                    val curIdx = parties.indexOfFirst { it.id == party.id }
                    if (curIdx != -1) notifyItemChanged(curIdx)

                    onSelectionChanged(party.id, candidate.id, candidate.displayName, party.name)
                }
                row.setOnClickListener { radio.isChecked = true }

                box.addView(row)
            }
        }
    }

    fun clearSelection() {
        val prev = selectedPartyId
        selectedPartyId     = null
        selectedCandidateId = null
        val idx = parties.indexOfFirst { it.id == prev }
        if (idx != -1) notifyItemChanged(idx)
    }
}