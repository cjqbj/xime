#pragma once

#include <string>
#include <utility>
#include <vector>

namespace xime {

inline int resolveCandidateIndex(
    const std::vector<std::pair<std::string, std::string>>& candidates,
    int preferredIndex,
    const std::string* expectedText,
    const std::string* expectedComment
) {
    if (!expectedText) return preferredIndex;

    const auto matches = [&](int index) {
        if (index < 0 || index >= static_cast<int>(candidates.size())) return false;
        const auto& candidate = candidates[index];
        return candidate.first == *expectedText &&
            (!expectedComment || candidate.second == *expectedComment);
    };

    if (matches(preferredIndex)) return preferredIndex;
    for (int i = 0; i < static_cast<int>(candidates.size()); ++i) {
        if (matches(i)) return i;
    }
    return -1;
}

}  // namespace xime
