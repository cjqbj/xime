#ifndef T9_USER_TRANSLATOR_H_
#define T9_USER_TRANSLATOR_H_

#include <rime/candidate.h>
#include <rime/common.h>
#include <rime/translation.h>
#include <rime/translator.h>

namespace rime {

// T9UserTranslation — T9 数字词典候选 Translation。
// quality 由 T9UserTranslator::Query 用 FormulaP 计算，在 Candidate::compare 中
// 与 RIME 候选在同一 quality 尺度内公平竞争。层内排序由 Lookup 的 dee 降序保证。
// 去重由 filter 链末尾的 uniquifier 兜底。
class T9UserTranslation : public FifoTranslation {
 public:
  T9UserTranslation() = default;
  int Compare(an<Translation> other,
              const CandidateList& candidates) override;
};

class T9UserTranslator : public Translator {
 public:
  explicit T9UserTranslator(const Ticket& ticket);
  an<Translation> Query(const string& input, const Segment& segment) override;

 private:
  double initial_quality_ = 0.0;  // 从 schema config 读取（继承 rime_ice 的 1.2）
};

}  // namespace rime

#endif  // T9_USER_TRANSLATOR_H_
