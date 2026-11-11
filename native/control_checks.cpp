#include "engine.hpp"
#include <iostream>
#include <stdexcept>
#include <thread>

void require(bool ok) { if(!ok) throw std::runtime_error("Native control check failed"); }
int main() {
    using namespace eightcb;
    Payload p{0x87,0xd0,0,0,0,0x2b,0xa4,0xd9,0xb5,0x18};
    auto pcm=encode_slot(p,1000);
    for (auto entry : {std::pair{WorkStage::preparing,1}, {WorkStage::monitoring,10},
                       {WorkStage::searching,2}, {WorkStage::decoding,2}, {WorkStage::snr,10}}) {
        auto stage=entry.first;
        int seen=0;
        try { decode_slot(pcm,1,[&](WorkStage current) { return current==stage && ++seen>=entry.second; });
              throw std::runtime_error("Cancellation ignored"); }
        catch (WorkCancelled const&) { require(seen==entry.second); }
        require(decode_slot(pcm).frames.size()==1); // Cleanup must permit a fresh full decode.
    }
    try { encode_slot(p,1000,.4f,[](WorkStage stage) {return stage==WorkStage::encoding;}); require(false); }
    catch (WorkCancelled const&) { }
    try { WorkBudget(0).check(WorkStage::preparing); require(false); } catch (WorkExpired const&) { }
    try {
        WorkBudget budget(1,[](WorkStage) { std::this_thread::sleep_for(std::chrono::milliseconds(3)); return false; });
        budget.check(WorkStage::monitoring); require(false);
    } catch (WorkExpired const&) { }
    try { decode_settings(2); require(false); } catch (std::invalid_argument const&) { }
    for(int profile=0;profile<2;++profile) {
        auto limits=decode_settings(profile);
        auto result=decode_slot(pcm,profile);
        require(result.frames.size()==1 && result.frames[0].payload==p);
        require(result.processed==result.candidates && result.candidates<=limits.candidates);
        auto early=pcm;
        early.resize(early_window_samples);
        auto captured=decode_slot(early,profile);
        require(captured.frames.size()==1 && captured.frames[0].payload==p);
        early.pop_back();
        try { decode_slot(early,profile); require(false); } catch (std::invalid_argument const&) { }
    }
    std::cout<<"Native stage cancellation, cleanup, deadline and profile bounds passed\n";
}
