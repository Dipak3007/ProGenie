<%@page contentType="text/html" pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html lang="en">

<head>
    <meta charset="UTF-8">
    <meta http-equiv="X-UA-Compatible" content="IE=edge">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Electricians</title>
    <link rel="icon" href="assets/images/logo.png" type="image/x-icon">
    <link rel="stylesheet" href="assets/css/style.css">
    <link rel="stylesheet" href="assets/css/electrician.css">
    <link rel="stylesheet" href="assets/css/form.css">

    <!-- Vendor CSS Files -->
    <link href="assets/css/vendor/animate.css/animate.min.css" rel="stylesheet">
    <link href="assets/css/vendor/bootstrap/css/bootstrap.min.css" rel="stylesheet">
    <link href="assets/css/vendor/bootstrap-icons/bootstrap-icons.css" rel="stylesheet">
    <link href="assets/css/vendor/boxicons/css/boxicons.min.css" rel="stylesheet">
    <link href="assets/css/vendor/glightbox/css/glightbox.min.css" rel="stylesheet">
    <link href="assets/css/vendor/swiper/swiper-bundle.min.css" rel="stylesheet">
</head>

<body style="background: #f3f9fc;">
    <input  type="hidden" id="status" value="<%= request.getAttribute("status") %>">
    <header class="myhead" id="myhead">
        <a href="index.jsp" class="logo1">
            <h2 class="logo">ProGenie</h2>
            <img src="assets/images/logo.png" alt="logo">
        </a>
        <nav class="navigation">
            <a href="index.jsp#myhead">Home</a>
            <a href="index.jsp#about">About</a>
            <a href="index.jsp#Services">Services</a>
            <a href="index.jsp#contact">Contact</a>
            <button onclick="window.location.href='Logout';" class="btnLogin-popup"><%= session.getAttribute("name") %></button>
        </nav>
    </header>
    <main>
        <div class="trending">
            <div class="container">
                <div class="wrapper">
                    <div class="topbox">
                        <p style="text-align: center;margin-bottom: 40px;font-size: 40px;font-weight: 600;">Electrician
                            Genie</p>
                    </div>
                    <div class="column">
                        <div class="flexwrap">
                            <div class="row products mini">
                                <div class="item">
                                    <div class="media">
                                        <div class="image">
                                            <a href="#">
                                                <img src="assets/images/elect1.png">
                                            </a>
                                        </div>
                                    </div>
                                    <div class="content">
                                        <h3 class="main-links"><a href="#">Ravi Kumar</a></h3>
                                        <p>Ravi Kumar is a professional electrician with over 10 years of experience. He
                                            is reliable, skilled in troubleshooting and repair, and committed to
                                            providing quality service to his clients.</p>
                                        <div class="rating">
                                            <div class="stars"></div>
                                            <span class="mini-text">(548)</span>
                                        </div>
                                        <div class="price">
                                            <span class="current">₹499</span>
                                            <span class="normal mini-text">₹749</span>
                                            <a href="#" class="genie-sign" data-name="Ravi Kumar">&nbsp;Book</a>
                                        </div>
                                        <div class="mini-text">
                                            <p>33% Off</p>
                                        </div>
                                    </div>
                                </div>
                            </div>
                            <hr>
                            <div class="row products mini">
                                <div class="item">
                                    <div class="media">
                                        <div class="image">
                                            <a href="#">
                                                <img src="assets/images/elect2.png">
                                            </a>
                                        </div>
                                    </div>
                                    <div class="content">
                                        <h3 class="main-links"><a href="#">Rahul Gupta</a></h3>
                                        <p>Rahul Gupta is a professional electrician with 8+ years of experience. He is
                                            skilled in diagnosing and repairing electrical issues and committed to
                                            providing quality service to his clients. Rahul is reliable,
                                            detail-oriented, and takes pride in his work.</p>
                                        <div class="rating">
                                            <div class="stars"></div>
                                            <span class="mini-text">(223)</span>
                                        </div>
                                        <div class="price">
                                            <span class="current">₹499</span>
                                            <span class="normal mini-text">₹749</span>
                                            <a href="#" class="genie-sign" data-name="Rahul Gupta">&nbsp;Book</a>
                                        </div>
                                        <div class="mini-text">
                                            <p>33% Off</p>
                                        </div>
                                    </div>
                                </div>
                            </div>
                            <hr>
                            <div class="row products mini">
                                <div class="item">
                                    <div class="media">
                                        <div class="image">
                                            <a href="#">
                                                <img src="assets/images/elec3.png">
                                            </a>
                                        </div>
                                    </div>
                                    <div class="content">
                                        <h3 class="main-links"><a href="#">Rajeev Verma</a></h3>
                                        <p>Rajeev Verma is a skilled and reliable electrician with 12+ years of
                                            experience. He has a strong understanding of electrical systems and is known
                                            for his ability to troubleshoot and repair various electrical issues. Rajeev
                                            is committed to providing high-quality service and takes great pride in his
                                            work.</p>
                                        <div class="rating">
                                            <div class="stars"></div>
                                            <span class="mini-text">(371)</span>
                                        </div>
                                        <div class="price">
                                            <span class="current">₹499</span>
                                            <span class="normal mini-text">₹749</span>
                                            <a href="#" class="genie-sign" data-name="Rajeev Verma">&nbsp;Book</a>
                                        </div>
                                        <div class="mini-text">
                                            <p>33% Off</p>
                                        </div>
                                    </div>
                                </div>
                            </div>
                            <hr>
                            <div class="row products mini">
                                <div class="item">
                                    <div class="media">
                                        <div class="image">
                                            <a href="#">
                                                <img src="assets/images/elec4.png">
                                            </a>
                                        </div>
                                    </div>
                                    <div class="content">
                                        <h3 class="main-links"><a href="#">Nitin Choudhary</a></h3>
                                        <p>Nitin Choudhary is a professional electrician with extensive experience in
                                            electrical maintenance, repair, and installation. He is known for his
                                            dedication to quality work and excellent customer service.</p>
                                        <div class="rating">
                                            <div class="stars"></div>
                                            <span class="mini-text">(193)</span>
                                        </div>
                                        <div class="price">
                                            <span class="current">₹499</span>
                                            <span class="normal mini-text">₹749</span>
                                            <a href="#" class="genie-sign" data-name="Nitin Choudhary">&nbsp;Book</a>
                                        </div>
                                        <div class="mini-text">
                                            <p>33% Off</p>
                                        </div>
                                    </div>
                                </div>
                            </div>
                            <hr>
                            <div class="row products mini">
                                <div class="item">
                                    <div class="media">
                                        <div class="image">
                                            <a href="#">
                                                <img src="assets/images/elec5.png">
                                            </a>
                                        </div>
                                    </div>
                                    <div class="content">
                                        <h3 class="main-links"><a href="#">Akash Sharma</a></h3>
                                        <p>Akash Sharma is a skilled electrician with a passion for solving complex
                                            electrical problems. He is dedicated to providing high-quality service and
                                            ensuring customer satisfaction through his work.</p>
                                        <div class="rating">
                                            <div class="stars"></div>
                                            <span class="mini-text">(390)</span>
                                        </div>
                                        <div class="price">
                                            <span class="current">₹499</span>
                                            <span class="normal mini-text">₹749</span>
                                            <a href="#" class="genie-sign" data-name="Akash Sharma">&nbsp;Book</a>
                                        </div>
                                        <div class="mini-text">
                                            <p>33% Off</p>
                                        </div>
                                    </div>
                                </div>
                            </div>
                            <hr>
                            <div class="row products mini">
                                <div class="item">
                                    <div class="media">
                                        <div class="image">
                                            <a href="#">
                                                <img src="assets/images/elec6.png">
                                            </a>
                                        </div>
                                    </div>
                                    <div class="content">
                                        <h3 class="main-links"><a href="#">Yogesh Patel</a></h3>
                                        <p>Yogesh Patel is a professional electrician with extensive experience in the
                                            field. He is known for his excellent problem-solving skills and commitment
                                            to providing high-quality service to his clients.</p>
                                        <div class="rating">
                                            <div class="stars"></div>
                                            <span class="mini-text">(172)</span>
                                        </div>
                                        <div class="price">
                                            <span class="current">₹499</span>
                                            <span class="normal mini-text">₹749</span>
                                            <a href="#" class="genie-sign" data-name="Yogesh Patel">&nbsp;Book</a>
                                        </div>
                                        <div class="mini-text">
                                            <p>33% Off</p>
                                        </div>
                                    </div>
                                </div>
                            </div>
                            <hr>
                            <div class="row products mini">
                                <div class="item">
                                    <div class="media">
                                        <div class="image">
                                            <a href="#">
                                                <img src="assets/images/elec7.png">
                                            </a>
                                        </div>
                                    </div>
                                    <div class="content">
                                        <h3 class="main-links"><a href="#">Tarun Gupta</a></h3>
                                        <p>Tarun Gupta is a skilled electrician with a passion for his work. He is known
                                            for his attention to detail and dedication to providing exceptional service
                                            to his clients.</p>
                                        <div class="rating">
                                            <div class="stars"></div>
                                            <span class="mini-text">(74)</span>
                                        </div>
                                        <div class="price">
                                            <span class="current">₹499</span>
                                            <span class="normal mini-text">₹749</span>
                                            <a href="#" class="genie-sign" data-name="Tarun Gupta">&nbsp;Book</a>
                                        </div>
                                        <div class="mini-text">
                                            <p>33% Off</p>
                                        </div>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    </main>

    <!-- form -->
    <div class="out">
        <div class="wrapper1">
            <div class="form-box" id="book_electrician">
                <form action="Booking" method="post">
                    <div class="input-box">
                        <span class="icon"><ion-icon name="person-circle"></ion-icon></span>
                        <input type="text" id="name" name="name" required>
                        <label>Genie Name</label>
                    </div>
                    <div class="input-box">
                        <span class="icon"><ion-icon name="build"></ion-icon></span>
                        <input type="text" value="Electrician" id="service" name="service" required>
                        <label>Service</label>
                    </div>
                    <div class="input-box date-input">
                        <span class="icon date-icon"><ion-icon name="calendar-number"></ion-icon></span>
                        <input type="date" class="date-box" id="doa" name="doa" required>
                        <label>Date of Appointment</label>
                    </div>
                    <div class="input-box">
                        <span class="icon"><ion-icon name="person"></ion-icon></span>
                        <input type="text" value="<%= session.getAttribute("cname") %>" id="cname" name="cname" required>
                        <label>Client Name</label>
                    </div>
                    <div class="input-box">
                        <span class="icon"><ion-icon name="call"></ion-icon></span>
                        <input type="number" value="<%= session.getAttribute("call") %>" id="call" name="call" required>
                        <label>Contact Number</label>
                    </div>
                    <div class="input-box">
                        <span class="icon"><ion-icon name="mail"></ion-icon></span>
                        <input type="email" value="<%= session.getAttribute("email") %>" id="email" name="email" required>
                        <label>Email</label>
                    </div>
                    <div class="input-box">
                        <span class="icon"><ion-icon name="home"></ion-icon></span>
                        <input type="text" id="add" name="add" required>
                        <label>Address</label>
                    </div>
                    <button type="submit" class="btn">Book Now</button>
                </form>
            </div>
        </div>
    </div>
    
    <!-- ======= Footer ======= -->
    <footer>
        <div class="footer-area">
            <div class="container">
                <div class="row" style="justify-content: space-between;">
                    <div class="col-md-4">
                        <div class="footer-content">
                            <div class="footer-head">
                                <a href="index.jsp" class="logo1 nav-link scrollto"
                                    style="justify-content: flex-start;padding-bottom: 18px;">
                                    <h2 class="logo" style="padding: 0;margin: 0;">ProGenie</h2>
                                    <img src="assets/images/logo.png" alt="logo">
                                </a>

                                <p>Our mission is to simplify your life by providing a reliable and convenient way to
                                    find and book home services in your area.</p>
                                <div class="footer-icons">
                                    <ul>
                                        <li>
                                            <a href="#"><i class="bi bi-facebook"></i></a>
                                        </li>
                                        <li>
                                            <a href="#"><i class="bi bi-twitter"></i></a>
                                        </li>
                                        <li>
                                            <a href="#"><i class="bi bi-instagram"></i></a>
                                        </li>
                                        <li>
                                            <a href="#"><i class="bi bi-linkedin"></i></a>
                                        </li>
                                    </ul>
                                </div>
                            </div>
                        </div>
                    </div>
                    <!-- end single footer -->
                    <div class="col-md-4">
                        <div class="footer-content">
                            <div class="footer-head">
                                <h4>information</h4>
                                <p>
                                    Thank you for choosing ProGenie for your home service needs. We look forward to
                                    providing you with a seamless and hassle-free experience.
                                </p>
                                <div class="footer-contacts">
                                    <p><span>Tel:</span> +91 (0771) 1234-5678</p>
                                    <p><span>Email:</span> admin@progenie.com</p>
                                    <p><span>Working Hours:</span> 9am-5pm</p>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
        <div class="footer-area-bottom">
            <div class="container">
                <div class="row">
                    <div class="col-md-12 col-sm-12 col-xs-12">
                        <div class="copyright text-center">
                            <p>
                                &copy; Copyright <strong>ProGenie</strong>. All Rights Reserved
                            </p>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    </footer><!-- End  Footer -->
    <script type="module" src="https://unpkg.com/ionicons@5.5.2/dist/ionicons/ionicons.esm.js"></script>
    <script nomodule src="https://unpkg.com/ionicons@5.5.2/dist/ionicons/ionicons.js"></script>
    <script>
        const bookBtn = document.getElementsByClassName('genie-sign');

        const closeModal = () => {
            document.querySelector('.out').style.display = "none";
            document.body.style.overflow = "auto";
        };
        document.querySelector('.out').addEventListener('click', closeModal);
        document.querySelector('.wrapper1').addEventListener('click', (e) => {
            e.stopPropagation();
        });
        const bookFun = (e) => {
            document.querySelector('.out').style.display = "flex";
            document.body.style.overflow = "hidden";
            document.getElementById('name').value = e.target.getAttribute("data-name");
        };
        
        for(let i = 0; i < bookBtn.length; i++) {
            bookBtn[i].addEventListener('click', bookFun);
        }
    </script>
    <script src="https://unpkg.com/sweetalert/dist/sweetalert.min.js"></script>
        <link rel="stylesheet" href="alert/dist/sweetalert.css">
        <script type="text/javascript">
            var status=document.getElementById("status").value;
            if(status == "success"){
                swal("ThankYou","Our Genie will reach you soon!","success");
            }
        </script>
</body>

</html>
