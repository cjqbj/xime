#include "t9_user_translator.h"

#include <rime/config.h>
#include <rime/context.h>
#include <rime/engine.h>
#include <rime/schema.h>

#include "t9_digit_userdict.h"
#include "t9_digit_userdict_core.h"
#include "t9_log.h"

namespace rime {

int T9UserTranslation::Compare(an<Translation> other,
                               const CandidateList& candidates) {
  if (exhausted())
    return 1;
  if (!other)
    return -1;
  // 按 Candidate::compare（start→end→quality 降序）公平竞争。
  // quality 由 Query 用 FormulaP 计算（与 RIME user_phrase 同公式同尺度），
  // 点击调频有效——点"激化"多次后 weight 上升能超过"几户表"，反之亦然。
  return Translation::Compare(other, candidates);
}

T9UserTranslator::T9UserTranslator(const Ticket& ticket) : Translator(ticket) {
  // 从 schema config 读 initial_quality（t9 schema __include rime_ice，
  // 继承 translator/initial_quality: 1.2）。
  if (auto* schema = ticket.schema) {
    if (auto* config = schema->config()) {
      config->GetDouble("translator/initial_quality", &initial_quality_);
    }
  }
}

an<Translation> T9UserTranslator::Query(const string& input,
                                        const Segment& segment) {
  T9_LOG_DEBUG("T9UserTranslator", ">> Query input='%s'", input.c_str());
  if (input.empty())
    return nullptr;

  std::string lookup_key = T9DigitUserDict::BuildLookupKey(input);
  if (lookup_key.empty())
    return nullptr;
  T9_LOG_DEBUG("T9UserTranslator", ">> Query lookup_key='%s'", lookup_key.c_str());

  auto entries = T9DigitUserDict::Instance().Lookup(lookup_key, 0);
  T9_LOG_DEBUG("T9UserTranslator", ">> Query hits=%zu", entries.size());
  if (entries.empty())
    return nullptr;

  // present_tick = T9 全局 tick + 1（对齐 RIME CreateDictEntry 的 tick_+1）。
  double present_tick = (double)T9DigitUserDict::Instance().tick() + 1;

  auto result = New<T9UserTranslation>();
  for (const auto& e : entries) {
    auto cand = New<SimpleCandidate>("t9_user", segment.start, segment.end,
                                     e.text, e.pinyin, e.pinyin);
    // quality 对齐 RIME user_phrase（script_translator.cc:657-659）：
    //   formula_p(0, commits/present_tick, present_tick, adj_dee) +
    //   initial_quality + quality_len/full_code_length
    // T9 数字词典的词消费整个输入序列（exact match），quality_len/full_code = 1.0。
    double adj_dee = T9DigitUserDictCore::FormulaD(
        0.0, present_tick, e.dee, (double)e.last_tick);
    double u = (double)e.commits / present_tick;
    double p = T9DigitUserDictCore::FormulaP(0.0, u, present_tick, adj_dee);
    double quality = p + initial_quality_ + 1.0;
    cand->set_quality(quality);
    T9_LOG_DEBUG("T9UserTranslator",
                 ">> Query: cand text='%s' quality=%.6f (commits=%d dee=%.4f)",
                 e.text.c_str(), quality, e.commits, e.dee);
    result->Append(cand);
  }
  return result;
}

}  // namespace rime
