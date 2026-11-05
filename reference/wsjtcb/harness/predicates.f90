! 8CBdroid test adapter: call the unmodified desktop predicates on stdin cases.
program reference_predicates
  use cb_callsigns, only: is_complete_cb_callsign, is_cb_type4_message
  implicit none
  character(len=512) :: line
  logical :: accepted
  integer :: status
  do
    read(*,'(A)',iostat=status) line
    if (status < 0) exit
    if (status /= 0 .or. line(2:2) /= '|') stop 1
    select case(line(1:1))
    case('C')
      accepted = is_complete_cb_callsign(line(3:))
    case('T')
      accepted = is_cb_type4_message(line(3:))
    case default
      stop 2
    end select
    write(*,'(I1)') merge(1,0,accepted)
  enddo
end program
