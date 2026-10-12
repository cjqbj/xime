#include <rime_api.h>

#include "rime_candidate_selection.h"

#include <algorithm>
#include <chrono>
#include <filesystem>
#include <iostream>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace {

using Candidate = std::pair<std::string, std::string>;

struct FinalizeOnExit {
  RimeApi* api;
  ~FinalizeOnExit() { api->finalize(); }
};

void require(bool condition, const std::string& message) {
  if (!condition) throw std::runtime_error(message);
}

std::vector<Candidate> current_page(RimeApi* api, RimeSessionId session,
                                    int* page_no, int* page_size,
                                    bool* is_last_page) {
  RIME_STRUCT(RimeContext, context);
  require(api->get_context(session, &context), "Rime returned no context");
  *page_no = context.menu.page_no;
  *page_size = context.menu.page_size;
  *is_last_page = context.menu.is_last_page;

  std::vector<Candidate> candidates;
  for (int i = 0; i < context.menu.num_candidates; ++i) {
    const auto& candidate = context.menu.candidates[i];
    candidates.emplace_back(candidate.text ? candidate.text : "",
                            candidate.comment ? candidate.comment : "");
  }
  api->free_context(&context);
  return candidates;
}

void type_query(RimeApi* api, RimeSessionId session, const std::string& query) {
  for (const char key : query) {
    require(api->process_key(session, key, 0),
            "Rime rejected query key");
  }
}

void verify_identity_resolution() {
  const std::vector<Candidate> candidates = {{"same", "first"}, {"same", "second"}};
  const std::string expected_text = "same";
  const std::string expected_comment = "second";
  require(xime::resolveCandidateIndex(candidates, 0, &expected_text,
                                      &expected_comment) == 1,
          "Candidate comment did not disambiguate duplicate text");
  const std::string missing = "not-on-this-page";
  require(xime::resolveCandidateIndex(candidates, 0, &missing, nullptr) == -1,
          "A candidate absent from the current page was accepted");
}

void verify_page_selection(RimeApi* api, RimeSessionId session,
                           int expected_page) {
  api->clear_composition(session);
  type_query(api, session, "aa");

  int first_page_no = -1;
  int page_size = 0;
  bool first_page_last = false;
  const auto first_page =
      current_page(api, session, &first_page_no, &page_size, &first_page_last);
  require(first_page_no == 0, "Rime did not start on page zero");
  require(page_size == 5, "Unexpected Rime page size");

  for (int page = 0; page < expected_page; ++page) {
    require(api->process_key(session, 0xff56, 0),
            "Rime rejected Page_Down");
  }

  int page_no = -1;
  int current_page_size = 0;
  bool is_last_page = false;
  const auto candidates =
      current_page(api, session, &page_no, &current_page_size, &is_last_page);
  require(page_no == expected_page, "Rime did not move to the requested page");
  require(current_page_size == page_size, "Rime page size changed");
  require(candidates.size() == 5, "Unexpected candidate count on page");
  require((expected_page == 2) == is_last_page,
          "Rime reported an incorrect last-page state");

  const int clicked_index = 3;
  const auto& clicked_candidate = candidates[clicked_index];
  const int resolved_index = xime::resolveCandidateIndex(
      candidates, 0, &clicked_candidate.first, &clicked_candidate.second);
  require(resolved_index == clicked_index,
          "Candidate identity did not resolve to the clicked item");

  if (expected_page > 0) {
    const int stale_index = xime::resolveCandidateIndex(
        candidates, 0, &first_page.front().first, &first_page.front().second);
    require(stale_index == -1,
            "A stale candidate from page one matched a later page");
  }

  const size_t absolute_index =
      static_cast<size_t>(page_no * current_page_size + resolved_index);
  require(api->select_candidate(session, absolute_index),
          "Rime rejected the selected candidate by absolute index");
  RIME_STRUCT(RimeCommit, commit);
  require(api->get_commit(session, &commit), "Rime did not produce a commit");
  require(clicked_candidate.first == (commit.text ? commit.text : ""),
          "Rime committed a different candidate than the clicked item");
  api->free_commit(&commit);
}

void verify_fu_second_page_selection(RimeApi* api, RimeSessionId session) {
  api->clear_composition(session);
  type_query(api, session, "fu");
  require(api->process_key(session, 0xff56, 0),
          "Rime rejected Page_Down for fu");

  int page_no = -1;
  int page_size = 0;
  bool is_last_page = false;
  const auto candidates =
      current_page(api, session, &page_no, &page_size, &is_last_page);
  require(page_no == 1, "fu did not move to its second page");
  require(std::any_of(candidates.begin(), candidates.end(),
                      [](const Candidate& candidate) {
                        return candidate.first == "芙";
                      }),
          "芙 was not on fu's second page");

  require(api->set_input(session, "f"), "Rime rejected the prefix probe");
  require(api->set_input(session, "fu"), "Rime rejected restoring fu");
  current_page(api, session, &page_no, &page_size, &is_last_page);
  require(page_no == 0, "set_input did not reset the candidate page");
  require(api->process_key(session, 0xff56, 0),
          "Rime rejected restoring fu page two");
  const auto restored_candidates =
      current_page(api, session, &page_no, &page_size, &is_last_page);
  require(page_no == 1, "fu candidate page was not restored after set_input");
  const auto clicked = std::find_if(
      restored_candidates.begin(), restored_candidates.end(),
      [](const Candidate& candidate) { return candidate.first == "芙"; });
  require(clicked != restored_candidates.end(),
          "芙 disappeared after restoring fu page two");
  const int local_index =
      static_cast<int>(clicked - restored_candidates.begin());
  const size_t absolute_index =
      static_cast<size_t>(page_no * page_size + local_index);

  require(api->select_candidate(session, absolute_index),
          "Rime rejected the fu second-page candidate");
  RIME_STRUCT(RimeCommit, commit);
  require(api->get_commit(session, &commit), "Rime did not commit 芙");
  require(std::string(commit.text ? commit.text : "") == "芙",
          "Selecting fu second-page 芙 committed a different candidate");
  api->free_commit(&commit);
}

}  // namespace

int main(int argc, char** argv) {
  try {
    require(argc == 3, "Usage: rime_key_candidate_test <shared-data> <user-data>");
    const std::string shared_data = std::filesystem::absolute(argv[1]).string();
    const std::string user_data = std::filesystem::absolute(argv[2]).string();
    std::filesystem::create_directories(user_data);

    RimeApi* api = rime_get_api();
    require(api != nullptr, "Unable to load librime API");
    FinalizeOnExit finalize{api};

    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = shared_data.c_str();
    traits.user_data_dir = user_data.c_str();
    traits.app_name = "xime-key-candidate-test";
    traits.distribution_name = "Xime";
    traits.distribution_code_name = "xime";
    traits.distribution_version = "test";
    api->setup(&traits);
    api->initialize(&traits);

    require(api->start_maintenance(False), "Unable to start Rime deployment");
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(60);
    while (api->is_maintenance_mode() &&
           std::chrono::steady_clock::now() < deadline) {
      std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
    require(!api->is_maintenance_mode(), "Rime deployment timed out");

    const RimeSessionId session = api->create_session();
    require(session != 0, "Unable to create Rime session");
    require(api->select_schema(session, "xime_keytest"),
            "Unable to select xime_keytest schema");

    verify_identity_resolution();
    verify_page_selection(api, session, 0);
    verify_page_selection(api, session, 1);
    verify_page_selection(api, session, 2);
    verify_fu_second_page_selection(api, session);

    api->destroy_session(session);
    std::cout << "PASS: key input, three-page candidate navigation, and "
                 "identity-based selection\n";
    return 0;
  } catch (const std::exception& error) {
    std::cerr << "FAIL: " << error.what() << '\n';
    return 1;
  }
}
